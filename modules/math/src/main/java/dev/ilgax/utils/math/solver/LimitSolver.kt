package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.RoundingMode

/** A limit value: finite (exact when possible), ±∞, or "bounded oscillation, no limit". */
internal sealed interface LV
internal class LFin(val exact: Surd?, val d: Double, val tex: String? = null) : LV
internal class LInf(val sign: Int) : LV
internal data object LOsc : LV

/** Test hook: lets a test replace what a method returned, to prove that the numeric safety check catches a wrong answer. */
internal object LimitHooks {
    @Volatile var tamper: ((LV) -> LV)? = null
}

/** The numeric safety check disagreed with the answer the methods produced. */
internal class LimitUnreliable(val why: String) : Exception(why)

private class Leaf(val node: Node, val pow: Int)
private class Low(val c: Surd, val m: Int)
/** term = ±c·√B with c, B rational functions. */
private class Rad(val term: Node, val c: RatFn, val b: RatFn)

private class Ctx(val lh: Int = 0, val conj: Int = 0, val depth: Int = 0) {
    fun next(lh: Int = this.lh, conj: Int = this.conj) = Ctx(lh, conj, depth + 1)
}

/**
 * Limits: `\lim_{x\to a}`, one-sided `a^{+}` / `a^{-}`, and `x\to\pm\infty`.
 *
 * Methods, tried in this order and shown as named steps: direct substitution (an extended-real evaluator that knows
 * infinities, signs of vanishing denominators and indeterminate forms), factor-and-cancel for rational functions (0/0),
 * the conjugate (one square root), leading terms at ±∞ (rational and algebraic), the standard limits (matched through
 * equivalent infinitesimals of a linear or polynomial argument, and `1^∞` through `e^{lim g·h}`), the rewriting of
 * `0·∞`, `∞−∞` and indeterminate powers, and finally L'Hôpital's rule (at most three rounds, derivatives from
 * [Differentiator] and [Alg]). Every answer is then checked numerically ([LimitCheck]) before it is shown.
 */
internal class LimitSolver(private val s: SolveSession) {
    private class Header(val v: Char, val pt: Pt, val ptNode: Node?, val side: Int, val body: String)

    // ------------------------------------------------------------------ notation

    private fun closing(t: String, open: Int): Int {
        var d = 0
        for (i in open until t.length) {
            if (t[i] == '{') d++
            if (t[i] == '}') { d--; if (d == 0) return i }
        }
        return -1
    }

    private fun parseNode(src: String): Node {
        val lines = LatexParser.parse(src, null, AngleMode.Radians)
        if (lines.size != 1 || lines[0].parts.size != 1) throw UnsupportedMath("an equation or several expressions where one function is expected")
        return lines[0].parts[0]
    }

    private fun header(latex: String): Header {
        var t = latex.trim()
        if (!t.startsWith("\\lim")) throw UnsupportedMath("a limit inside a larger expression")
        t = t.removePrefix("\\lim").trim().removePrefix("\\limits").trim()
        if (!t.startsWith("_")) throw SolveFailure("the limit needs “x → a” under it, like \\lim_{x\\to 2}")
        t = t.substring(1).trim()
        if (!t.startsWith("{")) throw SolveFailure("the limit needs “x → a” in braces under it, like \\lim_{x\\to 2}")
        val c = closing(t, 0)
        if (c < 0) throw SolveFailure("the braces under \\lim are not closed")
        val sub = t.substring(1, c).filter { !it.isWhitespace() }
        var rest = t.substring(c + 1).trim().trimEnd('=').trim()
        val to = sub.indexOf("\\to")
        if (to < 0) throw SolveFailure("the limit needs an arrow, like \\lim_{x\\to 2}")
        val vs = sub.substring(0, to)
        if (vs.length != 1 || !vs[0].isLetter()) {
            if (vs.contains(',') || vs.contains('(') || vs.length > 1) throw UnsupportedMath("multivariable limits")
            throw SolveFailure("the limit variable is not clear")
        }
        val v = vs[0]
        var target = sub.substring(to + 3)
        if (target.isEmpty()) throw SolveFailure("the limit needs a point after the arrow")
        var side = 0
        Regex("""\^\{?([+-])\}?$""").find(target)?.let { side = if (it.groupValues[1] == "+") 1 else -1; target = target.substring(0, it.range.first) }
        if (side == 0) Regex("""^(\d+(?:\.\d+)?)([+-])$""").find(target)?.let { side = if (it.groupValues[2] == "+") 1 else -1; target = it.groupValues[1] }
        if (target.isEmpty()) throw SolveFailure("the limit needs a point after the arrow")
        if (rest.contains("\\lim")) throw UnsupportedMath("a limit inside a limit")
        if (rest.contains('=')) throw UnsupportedMath("checking a stated answer of a limit (leave the right side empty)")
        if (rest.contains('!') || rest.contains("\\sum") || rest.contains("\\prod") || rest.contains("\\binom")) {
            throw UnsupportedMath("a limit of a sequence with factorials, sums or products")
        }
        if (rest.contains("\\partial") || rest.contains("\\int")) throw UnsupportedMath("a limit with an integral or partial derivative inside")
        if (rest.isEmpty()) throw SolveFailure("there is nothing to take the limit of")
        val pt: Pt
        var node: Node? = null
        when (target) {
            "\\infty", "+\\infty" -> pt = Pt(null, 1)
            "-\\infty" -> pt = Pt(null, -1)
            else -> {
                val n = parseNode(target)
                if (n.variableNames().isNotEmpty()) throw UnsupportedMath("a limit point that still has an unknown in it")
                val e = try { NodeExact.eval(n, emptyMap()) } catch (_: NotExact) { throw UnsupportedMath("a limit point that isn't an exact number") } catch (u: Undefined) { throw SolveFailure(u.why) }
                pt = Pt(e, 0)
                node = n
                side = if (side == 0) 0 else side
            }
        }
        if (pt.isInf) side = 0
        return Header(v, pt, node, side, rest)
    }

    // ------------------------------------------------------------------ entry

    fun solve(latex: String): SolveResult {
        if (latex.contains("\\\\")) throw UnsupportedMath("several lines with a limit")
        val h = header(latex)
        if (Regex("""(?<![A-Za-z\\])[fgh]\(""").containsMatchIn(h.body)) {
            throw UnsupportedMath("a limit of an unspecified function f(…) (write the function out, for example (x+h)^2 instead of f(x+h))")
        }
        val f = parseNode(h.body)
        val v = h.v
        if (f.hasAbsVar() && f.hasTrigVar()) throw UnsupportedMath("absolute values together with trigonometric functions in a limit")
        val vars = f.variableNames()
        val ptTex = when {
            h.pt.inf > 0 -> "\\infty"
            h.pt.inf < 0 -> "-\\infty"
            else -> DTex.tex(h.ptNode!!)
        }
        fun head(side: Int) = "\\lim_{$v\\to ${if (side == 0) ptTex else (if (ptTex.length > 1) "{$ptTex}" else ptTex) + (if (side > 0) "^{+}" else "^{-}")}}"
        if (f.hasTrig()) s.note("Trigonometric limits are in radians")
        s.step("${head(h.side)}${DTex.tex(f)}", "The limit")

        if (vars.any { it != v }) return finish(h, f, ::head, paramLimit(h, f))

        val run = { side: Int ->
            val r = Run(v, h.pt, h.ptNode, side).limit(f, Ctx())
            LimitHooks.tamper?.invoke(r) ?: r
        }
        val outcome: Outcome = when {
            h.pt.isInf -> Outcome(listOf(h.pt.inf to run(h.pt.inf)), null)
            h.side != 0 -> {
                if (!definedNear(f, v, h.pt, h.side)) throw SolveFailure("the function isn't defined just ${if (h.side > 0) "right" else "left"} of ${plainOf(ptTex)}")
                Outcome(listOf(h.side to run(h.side)), null)
            }
            else -> {
                val dl = definedNear(f, v, h.pt, -1)
                val dr = definedNear(f, v, h.pt, 1)
                if (!dl && !dr) throw SolveFailure("the function isn't defined near ${plainOf(ptTex)}")
                if (dl != dr) {
                    val sd = if (dr) 1 else -1
                    s.note("The function is only defined to the ${if (dr) "right" else "left"} of ${plainOf(ptTex)}, so that side is used")
                    s.step("${head(sd)}${DTex.tex(f)}", "Only the ${if (dr) "right" else "left"}-hand side is in the domain")
                    Outcome(listOf(sd to run(sd)), null)
                } else {
                    val mark = s.steps.size
                    val nmark = s.notes.size
                    try {
                        Outcome(listOf(0 to run(0)), null)
                    } catch (_: NeedSides) {
                        while (s.steps.size > mark) s.steps.removeAt(s.steps.size - 1)
                        while (s.notes.size > nmark) s.notes.removeAt(s.notes.size - 1)
                        s.step("${head(0)}${DTex.tex(f)}", "The two sides may differ, so each side is worked out separately")
                        s.step("${head(-1)}${DTex.tex(f)}", "Left-hand limit (x → ${plainOf(ptTex)} from below)")
                        val l = run(-1)
                        s.step("${head(-1)}${DTex.tex(f)}=${valueTex(l)}", "Left-hand limit")
                        s.step("${head(1)}${DTex.tex(f)}", "Right-hand limit (x → ${plainOf(ptTex)} from above)")
                        val r = run(1)
                        s.step("${head(1)}${DTex.tex(f)}=${valueTex(r)}", "Right-hand limit")
                        Outcome(listOf(-1 to l, 1 to r), null)
                    }
                }
            }
        }
        return finish(h, f, ::head, outcome)
    }

    private class Outcome(val sides: List<Pair<Int, LV>>, val param: Pair<String, Node>?)

    /** Numeric check, then the answer. */
    private fun finish(h: Header, f: Node, head: (Int) -> String, o: Outcome): SolveResult {
        val v = h.v
        val bodyTex = bodyTexOf(f)
        if (o.param != null) {
            val (tex, node) = o.param
            val bad = LimitCheck.checkParam(f, v, h.pt, node)
            if (bad != null) throw LimitUnreliable(bad)
            val line = "${head(h.side)}$bodyTex=$tex"
            return SolveResult.Solved(listOf(Answer(line, plain(h, f, plainOf(tex), "="))), s.steps, s.notes)
        }
        val vals = o.sides
        if (vals.size == 2) {
            // two one-sided values: each is checked on its own side
            for ((sd, lv) in vals) LimitCheck.check(f, v, h.pt, listOf(sd), lv)?.let { throw LimitUnreliable(it) }
            val (l, r) = vals[0].second to vals[1].second
            if (same(l, r)) {
                s.step("${valueTex(l)}", "Both one-sided limits are equal, so the limit exists")
                return answer(h, f, head, l)
            }
            s.step("${valueTex(l)}\\ne ${valueTex(r)}", "The one-sided limits differ")
            val line = "${head(0)}$bodyTex\\ \\text{Does not exist}"
            return SolveResult.Solved(
                listOf(Answer(line, "lim ${plainTex(bodyTex)} does not exist (left: ${plainOf(valueTex(l))}, right: ${plainOf(valueTex(r))})")),
                s.steps, s.notes,
            )
        }
        val (sd, lv) = vals[0]
        if (lv is LOsc) {
            val core = if (f is Node.Neg) f.arg else f
            if (core !is Node.Trig || core.name !in setOf("sin", "cos")) throw UnsupportedMath("a bounded oscillating expression whose limit this solver can't decide")
        }
        val sides = if (h.pt.isInf) listOf(h.pt.inf) else if (sd == 0) listOf(1, -1) else listOf(sd)
        LimitCheck.check(f, v, h.pt, sides, lv)?.let { throw LimitUnreliable(it) }
        if (lv is LOsc) {
            val line = "${head(h.side)}$bodyTex\\ \\text{Does not exist (oscillates)}"
            return SolveResult.Solved(listOf(Answer(line, "lim ${plainTex(bodyTex)} does not exist (oscillates between -1 and 1)")), s.steps, s.notes)
        }
        return answer(h, f, head, lv)
    }

    private fun bodyTexOf(f: Node) = if (f is Node.Sum) "\\left(${DTex.tex(f)}\\right)" else DTex.tex(f)

    private fun plainTex(t: String): String {
        var s = t.replace("\\left(", "(").replace("\\right)", ")")
        do {
            val before = s
            s = Regex("""\^\{([^{}]*[^{}0-9][^{}]*)\}""").replace(s) { val c = it.groupValues[1]; "^" + (if (c.length == 1) c else "($c)") }
        } while (s != before)
        return plainOf(s)
    }

    private fun answer(h: Header, f: Node, head: (Int) -> String, lv: LV): SolveResult {
        val bodyTex = bodyTexOf(f)
        val vt = valueTex(lv)
        val eq = if (lv is LFin && lv.exact == null && lv.tex == null) "" else "="
        val line = "${head(h.side)}$bodyTex$eq$vt"
        return SolveResult.Solved(listOf(Answer(line, plain(h, f, plainTex(vt), eq))), s.steps, s.notes)
    }

    private fun plain(h: Header, f: Node, value: String, eq: String): String {
        val pt = when { h.pt.inf > 0 -> "∞"; h.pt.inf < 0 -> "-∞"; else -> plainOf(DTex.tex(h.ptNode!!)) }
        val mark = if (h.side > 0) "⁺" else if (h.side < 0) "⁻" else ""
        return "lim ${h.v}→$pt$mark ${plainTex(bodyTexOf(f))}${if (eq.isEmpty()) " " else " = "}$value"
    }

    private fun same(a: LV, b: LV): Boolean = when {
        a is LInf && b is LInf -> a.sign == b.sign
        a is LFin && b is LFin -> if (a.exact != null && b.exact != null) a.exact == b.exact else Math.abs(a.d - b.d) <= 1e-9 * (1 + Math.abs(a.d))
        else -> false
    }

    private fun valueTex(lv: LV): String = when (lv) {
        is LInf -> if (lv.sign > 0) "\\infty" else "-\\infty"
        is LOsc -> "\\text{no limit}"
        is LFin -> when {
            lv.tex != null -> lv.tex + "\\approx" + Printer.num(lv.d, 4)
            lv.exact != null -> Printer.surd(lv.exact) + (Printer.decimal(lv.exact)?.let { (if (it.exact) "=" else "\\approx") + it.text } ?: "")
            else -> "\\approx" + Printer.num(lv.d, 4)
        }
    }

    private fun definedNear(f: Node, v: Char, pt: Pt, side: Int): Boolean {
        var ok = 0
        for (k in intArrayOf(3, 5, 7)) {
            val x = pt.d + side * Math.pow(10.0, -k.toDouble())
            try { val y = NodeNum.eval(f, mapOf(v to x)); if (!y.isNaN()) ok++ } catch (_: Undefined) {} catch (_: ArithmeticException) {}
        }
        return ok >= 2
    }

    // ------------------------------------------------------------------ the derivative-by-definition kind: other letters are parameters

    private fun paramLimit(h: Header, f: Node): Outcome {
        val v = h.v
        if (h.pt.isInf || h.pt.exact == null || !h.pt.exact.isZero) throw UnsupportedMath("a limit with another letter in it (only h → 0 of a polynomial quotient, like the limit definition of a derivative)")
        val ev = Evaluator(AtomTable())
        val rf = try { ev.eval(f) } catch (_: Exception) { null }
        if (rf == null || !ev.atoms.isEmpty) throw UnsupportedMath("a limit with another letter in it that is not a polynomial quotient")
        var n = rf.num
        var d = rf.den
        s.step(Printer.ratFn(rf), "Expand numerator and denominator")
        var guard = 0
        while (n.substitute(v, Surd.ZERO).isZero && d.substitute(v, Surd.ZERO).isZero && guard++ < 8) {
            val g = n.monomialGcd().gcd(d.monomialGcd())
            if (g.exp(v) == 0) break
            val gv = Mono.of(v, g.exp(v))
            val gp = Poly.of(gv, Surd.ONE)
            val n1 = n.divMono(gv)
            val d1 = d.divMono(gv)
            s.step("\\frac{${Printer.product(gp, n1)}}{${Printer.product(gp, d1)}}", "Factor out ${Printer.poly(gp)} (the 0/0 form)")
            n = n1; d = d1
            s.step(Printer.ratFn(RatFn(n, d)), "Cancel ${Printer.poly(gp)}")
        }
        val na = n.substitute(v, Surd.ZERO)
        val da = d.substitute(v, Surd.ZERO)
        if (da.isZero) throw UnsupportedMath("a limit with another letter in it that does not cancel")
        val res = RatFn(na, da).simplified().value
        val tex = Printer.ratFn(res)
        s.step(tex, "Substitute $v = 0")
        val node = parseNode(tex)
        return Outcome(emptyList(), tex to node)
    }

    // ------------------------------------------------------------------ the engine

    private inner class Run(val v: Char, val pt: Pt, val ptNode: Node?, val side: Int) {
        private var lastUnsupported: UnsupportedMath? = null

        private fun step(l: String, c: String) = s.step(l, c)

        private fun ptSubstNode(): Node? = when {
            pt.isInf -> null
            pt.exact!!.isRational -> Node.Number(pt.exact.rational)
            else -> ptNode ?: surdNode(pt.exact)
        }

        private fun substTex(f: Node): String {
            val a = ptSubstNode() ?: return DTex.tex(f)
            return DTex.tex(f.rewrite { if (it is Node.Variable && it.name == v) a else null })
        }

        private fun ratOf(n: Node): RatFn? = try {
            val ev = Evaluator(AtomTable())
            val r = ev.eval(n)
            if (ev.atoms.isEmpty) r else null
        } catch (_: Exception) { null }

        private fun rollback(steps: Int, notes: Int) {
            while (s.steps.size > steps) s.steps.removeAt(s.steps.size - 1)
            while (s.notes.size > notes) s.notes.removeAt(s.notes.size - 1)
        }

        private fun attempt(block: () -> LV?): LV? {
            val m = s.steps.size
            val n = s.notes.size
            val r = try { block() } catch (u: UnsupportedMath) { lastUnsupported = u; null }
            if (r == null) rollback(m, n)
            return r
        }

        private fun formTex(form: String) = when (form) {
            "0/0" -> "\\frac{0}{0}"
            "∞/∞" -> "\\frac{\\infty}{\\infty}"
            "0·∞" -> "0\\cdot\\infty"
            "∞−∞" -> "\\infty-\\infty"
            "1^∞" -> "1^{\\infty}"
            "0^0" -> "0^{0}"
            "∞^0" -> "\\infty^{0}"
            else -> "\\text{indeterminate}"
        }

        /** An equivalent of f with every |u| whose u vanishes at the point replaced by ±u for the side taken. */
        private fun resolveAbs(f: Node): Node {
            if (!f.hasAbsVar()) return f
            val ex = Ext(v, pt, side)
            var changed = false
            val sub = ArrayList<String>()
            val out = f.rewrite { n ->
                if (n is Node.Abs && n.arg.hasVariable()) {
                    val a = try { ex.eval(n.arg) } catch (_: Exception) { null }
                    val vanishes = a is EF && a.isZero
                    val big = a is EInf
                    if (vanishes || big) {
                        if (!pt.isInf && side == 0) throw NeedSides()
                        val sg = if (pt.isInf) (if (a is EInf && a.sign != 0) a.sign else signSample(n.arg)) else signSample(n.arg)
                        if (sg == 0) null else {
                            changed = true
                            sub += "|${plainOf(DTex.tex(n.arg))}| = ${if (sg > 0) "" else "-"}(${plainOf(DTex.tex(n.arg))})"
                            if (sg > 0) n.arg else NB.neg(n.arg)
                        }
                    } else null
                } else null
            }
            if (!changed) return f
            val where = if (pt.isInf) "x → ${if (pt.inf > 0) "∞" else "-∞"}" else "$v → ${plainOf(DTex.tex(ptSubstNode()!!))}${if (side > 0) "⁺" else "⁻"}"
            step(DTex.tex(out), "For $where: ${sub.joinToString(", ")}")
            return out
        }

        private fun signSample(n: Node): Int {
            var sg = 0
            for (k in intArrayOf(2, 3, 4)) {
                val x = if (pt.isInf) pt.inf * Math.pow(10.0, (k + 1).toDouble()) else pt.d + side * Math.pow(10.0, -k.toDouble())
                val y = try { NodeNum.eval(n, mapOf(v to x)) } catch (_: Exception) { return 0 }
                if (y == 0.0 || y.isNaN()) return 0
                val t = if (y > 0) 1 else -1
                if (sg != 0 && sg != t) return 0
                sg = t
            }
            return sg
        }

        fun limit(f0: Node, c: Ctx): LV {
            if (c.depth > 14) throw UnsupportedMath("a limit that needs too many rewriting steps")
            val f = resolveAbs(f0)
            val ex = Ext(v, pt, side)
            var form: String? = null
            val e: EV? = try { ex.top(f) } catch (i: Indet) { form = i.form; null } catch (u: Undefined) { throw SolveFailure("the function isn't defined here: ${u.why}") }
            when (e) {
                is EF -> {
                    val lv = LFin(e.exact, e.d)
                    val squeeze = if (ex.squeezed) " (squeeze: sin and cos stay between −1 and 1, and the other factor → 0)" else ""
                    if (pt.isInf) step("${DTex.tex(f)}\\to ${valueTex(lv)}", "Evaluate as $v → ${if (pt.inf > 0) "∞" else "-∞"}: every term settles$squeeze")
                    else if (v !in f.variableNames()) step("${DTex.tex(f)}=${valueTex(lv)}", "Evaluate")
                    else step("${substTex(f)}=${valueTex(lv)}", "Direct substitution: the function is continuous at $v = ${plainOf(DTex.tex(ptSubstNode()!!))}$squeeze")
                    return lv
                }
                is EInf -> {
                    val inf = LInf(e.sign)
                    val sideWord = if (pt.isInf) "" else when (side) { 1 -> " from the right"; -1 -> " from the left"; else -> "" }
                    if (pt.isInf) step("${DTex.tex(f)}\\to ${valueTex(inf)}", "Evaluate as $v → ${if (pt.inf > 0) "∞" else "-∞"}: the expression grows without bound")
                    else step("${DTex.tex(f)}\\to ${valueTex(inf)}", (if (f.any { it is Node.Quotient }) "Division by 0 with a non-zero numerator" else "The expression has a pole here") + ": its sign$sideWord is ${if (e.sign > 0) "+" else "-"}, so the limit is ${if (e.sign > 0) "+∞" else "-∞"}")
                    return inf
                }
                EBounded -> {
                    step(DTex.tex(f), "sin or cos of something that runs off to infinity keeps oscillating between −1 and 1: no limit")
                    return LOsc
                }
                null -> {}
            }
            val fm = form!!
            step(formTex(fm), "Direct substitution gives the indeterminate form $fm")
            val methods: List<() -> LV?> = listOf(
                { if (pt.isInf) m4(f, c) else m2(f, c) },
                { m3(f, c) },
                { if (c.lh > 0 && c.lh < 3) null else m5(f, c, fm) },
                { forms(f, c, fm) },
                { lhopital(f, c) },
            )
            for (m in methods) attempt(m)?.let { return it }
            lastUnsupported?.let { throw it }
            throw UnsupportedMath("a limit these methods don't reach (the standard limits and three rounds of L'Hôpital's rule are all it has)")
        }

        // -------------------------------------------------------------- method 2: factor and cancel (0/0, rational functions)

        private fun m2(f: Node, c: Ctx): LV? {
            val a = pt.exact ?: return null
            if (!a.isRational) return null
            val rf = ratOf(f) ?: return null
            if (rf.variables.any { it != v }) return null
            val n = rf.num
            val d = rf.den
            if (!(n.substitute(v, a).isZero && d.substitute(v, a).isZero)) return null
            val p = Poly.variable(v) - Poly.const(a)
            val n1 = Poly.exactDivide(n, p, v) ?: return null
            val d1 = Poly.exactDivide(d, p, v) ?: return null
            val pTex = "(${Printer.poly(p)})"
            fun fac(q: Poly) = when {
                q == Poly.ONE -> pTex
                q.isConstant -> (if (q.constant == -Surd.ONE) "-" else Printer.poly(q)) + pTex
                else -> "$pTex(${Printer.poly(q)})"
            }
            step("\\frac{${fac(n1)}}{${fac(d1)}}", "Rational function, 0/0: factor $pTex out of the numerator and the denominator")
            val cancelled = RatFn(n1, d1)
            step(Printer.ratFn(cancelled), "Cancel the common factor $pTex")
            return limit(parseNode(Printer.ratFn(cancelled)), c.next())
        }

        // -------------------------------------------------------------- method 4: ±∞, leading terms

        private fun polyOf(n: Node): Poly? {
            val r = ratOf(n) ?: return null
            if (!r.den.isConstant || r.variables.any { it != v }) return null
            return try { r.num.divBy(r.den.constant) } catch (_: UnsupportedMath) { null }
        }

        private fun ltTex(c: Surd, d: Rational, u: Char): String {
            val coef = when {
                d.isZero -> return Printer.surd(c)
                c == Surd.ONE -> ""
                c == -Surd.ONE -> "-"
                c.isSingleTerm || c.isRational -> Printer.surd(c)
                else -> "(${Printer.surd(c)})"
            }
            val pw = when {
                d == Rational.ONE -> "$u"
                d.isInteger -> "$u^{$d}"
                d == Rational.of(1, 2) -> "\\sqrt{$u}"
                else -> "$u^{${Printer.rational(d)}}"
            }
            return coef + pw
        }

        private fun divided(p: Poly, n: Int): String {
            val sb = StringBuilder()
            for ((i, e) in p.terms.entries.sortedByDescending { it.key.exp(v) }.withIndex()) {
                val k = e.key.exp(v)
                val c = e.value
                val neg = c.terms.entries.minByOrNull { it.key }!!.value.signum < 0
                val mag = if (neg) -c else c
                val body = if (k == n) Printer.surd(mag) else "\\frac{${Printer.surd(mag)}}{${if (n - k == 1) "$v" else "$v^{${n - k}}"}}"
                if (i == 0) sb.append(if (neg) "-" else "") else sb.append(if (neg) "-" else "+")
                sb.append(body)
            }
            return if (sb.isEmpty()) "0" else sb.toString()
        }

        private fun m4(f: Node, c: Ctx): LV? {
            if (!pt.isInf) return null
            val sg = pt.inf
            val q = f as? Node.Quotient
            val pn = q?.let { polyOf(it.num) }
            val pd = q?.let { polyOf(it.den) }
            val infTxt = if (sg > 0) "∞" else "-∞"
            if (q != null && pn != null && pd != null && !pd.isZero && !pn.isZero) {
                val dn = pn.degreeIn(v)
                val dd = pd.degreeIn(v)
                val n = maxOf(dn, dd)
                val cn = pn.coeffOf(v, dn).constant
                val cd = pd.coeffOf(v, dd).constant
                step("\\frac{${divided(pn, n)}}{${divided(pd, n)}}", "$v → $infTxt, rational function: divide numerator and denominator by $v${if (n > 1) "^{$n}" else ""} (the highest power)")
                return when {
                    dn == dd -> {
                        val r = cn / cd
                        step("\\frac{${Printer.surd(cn)}}{${Printer.surd(cd)}}=${Printer.surd(r)}", "Equal degrees: the terms with $v in a denominator → 0, the limit is the ratio of the leading coefficients")
                        LFin(r, r.toDouble())
                    }
                    dn < dd -> {
                        step("\\frac{${ltTex(cn, Rational.of(dn.toLong()), v)}}{${ltTex(cd, Rational.of(dd.toLong()), v)}}\\to 0", "The denominator has the higher degree: the limit is 0")
                        LFin(Surd.ZERO, 0.0)
                    }
                    else -> {
                        val ratio = cn / cd
                        val diff = dn - dd
                        val sgn = ratio.signum() * (if (sg < 0 && diff % 2 == 1) -1 else 1)
                        step("\\frac{${ltTex(cn, Rational.of(dn.toLong()), v)}}{${ltTex(cd, Rational.of(dd.toLong()), v)}}=${ltTex(ratio, Rational.of(diff.toLong()), v)}\\to ${if (sgn > 0) "\\infty" else "-\\infty"}",
                            "The numerator has the higher degree: the limit is ±∞; the sign of ${plainOf(ltTex(ratio, Rational.of(diff.toLong()), v))} as $v → $infTxt is ${if (sgn > 0) "+" else "-"}")
                        LInf(sgn)
                    }
                }
            }
            // algebraic (roots): v = s·t with t → +∞
            val u: Char = if (sg > 0) v else if (v == 't') 'u' else 't'
            val g = if (sg > 0) f else f.rewrite { if (it is Node.Variable && it.name == v) Node.Neg(Node.Variable(u)) else null }
            val lead = Lead(u, 1)
            val total: Pair<Surd, Rational>?
            val parts: Pair<String, String>?
            try {
                if (g is Node.Quotient) {
                    val a = lead.of(g.num)
                    val b = lead.of(g.den) ?: throw Undefined("a denominator is 0")
                    total = a?.let { (it.first / b.first) to (it.second - b.second) }
                    parts = if (a == null) null else ltTex(a.first, a.second, u) to ltTex(b.first, b.second, u)
                } else {
                    total = lead.of(g)
                    parts = null
                }
            } catch (_: Cancel) { return null } catch (_: NotAlgebraic) { return null } catch (u2: Undefined) { throw SolveFailure(u2.why) }
            if (sg < 0) step(DTex.tex(g), "Put $v = −$u, so $u → ∞ (and |$v| = $u)")
            if (parts != null) step("\\frac{${parts.first}}{${parts.second}}", "Leading terms as $u → ∞ (each square root is read at its highest power)")
            else if (total != null) step(ltTex(total.first, total.second, u), "Leading term as $u → ∞")
            if (total == null) return LFin(Surd.ZERO, 0.0)
            val (cc, dd) = total
            return when {
                dd.isZero -> { step("${Printer.surd(cc)}", "Equal growth: the ratio of the leading coefficients"); LFin(cc, cc.toDouble()) }
                dd.signum < 0 -> { step("${ltTex(cc, dd, u)}\\to 0", "The denominator grows faster: the limit is 0"); LFin(Surd.ZERO, 0.0) }
                else -> { val sgn = cc.signum(); step("${ltTex(cc, dd, u)}\\to ${if (sgn > 0) "\\infty" else "-\\infty"}", "The numerator grows faster: the limit is ${if (sgn > 0) "+∞" else "-∞"}"); LInf(sgn) }
            }
        }

        // -------------------------------------------------------------- method 3: conjugate


        private fun flatten(n: Node, p: Int, out: MutableList<Leaf>) {
            when (n) {
                is Node.Product -> n.factors.forEach { flatten(it, p, out) }
                is Node.Quotient -> { flatten(n.num, p, out); flatten(n.den, -p, out) }
                is Node.Neg -> { out += Leaf(Node.Number(Rational.of(-1)), p); flatten(n.arg, p, out) }
                else -> out += Leaf(n, p)
            }
        }

        private fun hasRoot(n: Node) = n.any { it is Node.Root }


        private fun radTerm(t: Node): Rad? {
            val leaves = ArrayList<Leaf>()
            flatten(t, 1, leaves)
            val roots = leaves.filter { it.node is Node.Root }
            if (roots.size != 1 || roots[0].pow != 1 || (roots[0].node as Node.Root).index != 2) return null
            var c = RatFn(Poly.ONE, Poly.ONE)
            for (l in leaves) {
                if (l.node is Node.Root) continue
                val r = ratOf(l.node) ?: return null
                if (r.num.isZero) return null
                c = c * r.pow(l.pow)
            }
            val b = ratOf((roots[0].node as Node.Root).arg) ?: return null
            return Rad(t, c, b)
        }

        private fun m3(f: Node, c: Ctx): LV? {
            if (c.conj >= 3 || !hasRoot(f)) return null
            val leaves = ArrayList<Leaf>()
            flatten(f, 1, leaves)
            var rat = RatFn(Poly.ONE, Poly.ONE)
            val others = ArrayList<Leaf>()
            var target: Leaf? = null
            val ex = Ext(v, pt, side)
            for (l in leaves) {
                val node = l.node
                val r = if (hasRoot(node)) null else ratOf(node)
                when {
                    r != null -> { if (r.num.isZero) return null; rat = rat * r.pow(l.pow) }
                    node is Node.Sum && hasRoot(node) -> {
                        val vanishes = try { val e = ex.eval(node); e is EF && e.isZero } catch (_: Indet) { true } catch (_: Exception) { false }
                        if (target == null && vanishes) target = l else others += l
                    }
                    node is Node.Root -> others += l
                    else -> return null
                }
            }
            val t = target ?: return null
            val sum = t.node as Node.Sum
            val rads = sum.terms.mapNotNull { radTerm(it) }
            val rest = sum.terms.filter { tt -> rads.none { it.term === tt } }
            val rationalPart = try {
                rest.fold(RatFn(Poly.ZERO, Poly.ONE)) { acc, tt -> acc + (ratOf(tt) ?: return null) }
            } catch (_: Exception) { return null }
            val w: RatFn
            val conjTerms: List<Node>
            when {
                rads.size == 1 -> {
                    w = rationalPart * rationalPart - rads[0].c * rads[0].c * rads[0].b
                    conjTerms = sum.terms.map { if (it === rads[0].term) NB.neg(it) else it }
                }
                rads.size == 2 && rest.isEmpty() -> {
                    w = rads[0].c * rads[0].c * rads[0].b - rads[1].c * rads[1].c * rads[1].b
                    conjTerms = listOf(sum.terms[0], NB.neg(sum.terms[1]))
                }
                else -> return null
            }
            if (w.num.isZero) return null
            val conj = Node.Sum(conjTerms)
            val sTex = DTex.tex(sum)
            val cTex = DTex.tex(conj)
            step("\\left(${DTex.tex(f)}\\right)\\cdot\\frac{$cTex}{$cTex}",
                "Conjugate: a square root in ${if (t.pow > 0) "the numerator" else "the denominator"}, multiply and divide by $cTex")
            step("($sTex)($cTex)=${Printer.ratFn(w)}", "Difference of squares: the root disappears")
            val ratNew = if (t.pow > 0) rat * w else rat / w
            val newLeaves = others + Leaf(conj, -t.pow)
            val cancelled = ratNew.simplified().value
            val num = ArrayList<String>()
            val den = ArrayList<String>()
            if (cancelled.num != Poly.ONE) num += "(${Printer.poly(cancelled.num)})"
            if (cancelled.den != Poly.ONE) den += "(${Printer.poly(cancelled.den)})"
            for (l in newLeaves) { val tx = "(${DTex.tex(l.node)})"; if (l.pow > 0) num += tx else den += tx }
            val tex = if (den.isEmpty()) num.joinToString("").ifEmpty { "1" } else "\\frac{${num.joinToString("").ifEmpty { "1" }}}{${den.joinToString("")}}"
            val g = parseNode(tex)
            if (!equivalent(f, g)) return null
            step(DTex.tex(g), "Simplify and cancel the common factor")
            return limit(g, c.next(conj = c.conj + 1))
        }

        private fun equivalent(a: Node, b: Node): Boolean {
            var ok = 0
            val xs = listOf(0.37, 0.91, 1.73, 2.9, -0.43, -1.37, 5.3, -4.1, 0.05, 12.5)
            for (x in xs + (if (pt.isInf) emptyList() else listOf(pt.d + 0.013, pt.d - 0.017))) {
                val va = try { NodeNum.eval(a, mapOf(v to x)) } catch (_: Exception) { continue }
                val vb = try { NodeNum.eval(b, mapOf(v to x)) } catch (_: Exception) { continue }
                if (va.isNaN() || vb.isNaN() || va.isInfinite() || vb.isInfinite()) continue
                if (Math.abs(va - vb) > 1e-7 * (1 + Math.abs(va))) return false
                ok++
            }
            return ok >= 2
        }

        // -------------------------------------------------------------- method 5: standard limits


        private fun lowPoly(p: Poly, a: Surd): Low? {
            val h = Poly.variable(v)
            var q = Poly.ZERO
            for (k in 0..p.degreeIn(v)) {
                val ck = p.coeffOf(v, k)
                if (ck.isZero) continue
                q += ck * (h + Poly.const(a)).pow(k)
            }
            if (q.variables.any { it != v }) return null
            for (k in 0..q.degreeIn(v)) {
                val ck = q.coeffOf(v, k)
                if (!ck.isZero) return Low(ck.constant, k)
            }
            return null
        }

        private fun isMinusOne(n: Node) = (n is Node.Neg && n.arg == Node.Number(Rational.ONE)) || (n is Node.Number && n.value == Rational.of(-1))

        /** The standard infinitesimal equivalent of a factor whose argument u(a)=0: (kind, polynomial). */
        private fun equivOf(n: Node, a: Surd): Pair<String, Poly>? {
            fun vanishing(x: Node): Poly? { val p = polyOf(x) ?: return null; return if (p.substitute(v, a).isZero) p else null }
            val half = Surd.of(Rational.of(1, 2))
            return when {
                n is Node.Trig && n.name in setOf("sin", "tan", "arcsin", "arctan") -> vanishing(n.arg)?.let { n.name to it }
                n is Node.Func && n.name == "ln" -> polyOf(n.arg)?.takeIf { it.substitute(v, a) == Poly.ONE }?.let { "ln" to (it - Poly.ONE) }
                n is Node.Sum && n.terms.size == 2 -> {
                    val t0 = n.terms[0]; val t1 = n.terms[1]
                    fun cosPart(x: Node): Node.Trig? = (x as? Node.Neg)?.arg?.let { it as? Node.Trig }?.takeIf { it.name == "cos" }
                    val one = Node.Number(Rational.ONE)
                    val cs = when { t0 == one -> cosPart(t1); t1 == one -> cosPart(t0); else -> null }
                    if (cs != null) vanishing(cs.arg)?.let { "cos" to (it * it).times(half) }
                    else {
                        val ex = when { isMinusOne(t1) -> t0; isMinusOne(t0) -> t1; else -> null }
                        if (ex is Node.Power && ex.base == Node.Const('e')) vanishing(ex.exp)?.let { "exp" to it } else null
                    }
                }
                else -> null
            }
        }

        private fun lowOf(n: Node, a: Surd, used: MutableList<String>): Low? {
            when (n) {
                is Node.Neg -> return lowOf(n.arg, a, used)?.let { Low(-it.c, it.m) }
                is Node.Product -> {
                    var c = Surd.ONE; var m = 0
                    for (f in n.factors) { val l = lowOf(f, a, used) ?: return null; c *= l.c; m += l.m }
                    return Low(c, m)
                }
                is Node.Quotient -> {
                    val x = lowOf(n.num, a, used) ?: return null
                    val y = lowOf(n.den, a, used) ?: return null
                    return Low(x.c / y.c, x.m - y.m)
                }
                is Node.Power -> if (n.exp.variableNames().isEmpty() && n.base !is Node.Variable) {
                    val e = try { NodeExact.eval(n.exp, emptyMap()) } catch (_: NotExact) { return null }
                    if (e.isRational && e.rational.isInteger && e.rational.num.abs() <= java.math.BigInteger.valueOf(12)) {
                        val k = e.rational.num.toInt()
                        val b = lowOf(n.base, a, used) ?: return null
                        return Low(b.c.pow(k), b.m * k)
                    }
                }
                else -> {}
            }
            equivOf(n, a)?.let { (kind, p) -> used += kind; return lowPoly(p, a) }
            try {
                val e = Ext(v, pt, side).eval(n)
                if (e is EF && !e.isZero && e.exact != null) return Low(e.exact, 0)
            } catch (_: Exception) {}
            val r = ratOf(n) ?: return null
            if (r.variables.any { it != v }) return null
            val x = lowPoly(r.num, a) ?: return null
            val y = lowPoly(r.den, a) ?: return null
            return Low(x.c / y.c, x.m - y.m)
        }

        private fun standardTex(kind: String) = when (kind) {
            "sin" -> "\\lim_{u\\to0}\\frac{\\sin u}{u}=1"
            "tan" -> "\\lim_{u\\to0}\\frac{\\tan u}{u}=1"
            "arcsin" -> "\\lim_{u\\to0}\\frac{\\arcsin u}{u}=1"
            "arctan" -> "\\lim_{u\\to0}\\frac{\\arctan u}{u}=1"
            "cos" -> "\\lim_{u\\to0}\\frac{1-\\cos u}{u^{2}}=\\frac{1}{2}"
            "exp" -> "\\lim_{u\\to0}\\frac{e^{u}-1}{u}=1"
            else -> "\\lim_{u\\to0}\\frac{\\ln(1+u)}{u}=1"
        }

        private fun m5(f: Node, c: Ctx, form: String): LV? {
            if (f is Node.Power && form == "1^∞") return oneToInf(f, c)
            if (pt.isInf) return null
            val a = pt.exact ?: return null
            val used = ArrayList<String>()
            val low = try { lowOf(f, a, used) } catch (_: UnsupportedMath) { null } catch (_: ArithmeticException) { null } ?: return null
            if (used.isEmpty() || low.m < 0) return null
            val value = if (low.m > 0) Surd.ZERO else low.c
            for (k in used.distinct()) step(standardTex(k), "Standard limit (u → 0, angles in radians)")
            val replaced = f.rewrite { n -> equivOf(n, a)?.let { parseNode(Printer.poly(it.second)) } }
            step("${DTex.tex(replaced)}=${Printer.surd(value)}", "Replace each standard factor by its equivalent (sin u ~ u, tan u ~ u, 1−cos u ~ u²/2, e^u−1 ~ u, ln(1+u) ~ u) and simplify")
            return LFin(value, value.toDouble())
        }

        /** (1+g)^h with g → 0 and h → ∞: the limit is e^{lim g·h}. */
        private fun oneToInf(f: Node.Power, c: Ctx): LV? {
            val g = simplified(Node.Sum(listOf(f.base, Node.Number(Rational.of(-1)))))
            val gh = simplified(Node.Product(listOf(f.exp, g)))
            step("\\lim\\left(${DTex.tex(f)}\\right)=e^{\\lim ${DTex.tex(gh)}}", "Standard limit: (1+g)^h with g → 0 and h → ∞ is e^(lim g·h)")
            val inner = limit(gh, c.next())
            return expOf(inner).also { step("e^{${valueTex(inner).substringBefore("\\approx").substringBefore("=")}}=${valueTex(it)}", "The limit of the power") }
        }

        private fun expOf(l: LV): LV = when (l) {
            is LInf -> if (l.sign > 0) l else LFin(Surd.ZERO, 0.0)
            is LOsc -> throw UnsupportedMath("an exponent that oscillates")
            is LFin -> {
                val r = l.exact
                when {
                    r != null && r.isRational && r.rational.isZero -> LFin(Surd.ONE, 1.0)
                    r != null && r.isRational && r.rational.isInteger && r.rational.num.abs() <= java.math.BigInteger.valueOf(40) ->
                        Surd.E_SURD.pow(r.rational.num.toInt()).let { LFin(it, it.toDouble()) }
                    r != null && r.isRational && r.rational.den == java.math.BigInteger.TWO && r.rational.num.abs() <= java.math.BigInteger.valueOf(40) ->
                        Surd.constant(0, r.rational.num.toInt()).let { LFin(it, it.toDouble()) }
                    r != null -> LFin(null, Math.exp(l.d), "e^{${Printer.surd(r)}}")
                    else -> LFin(null, Math.exp(l.d))
                }
            }
        }

        private fun simplified(n: Node): Node {
            val s2 = try { Alg(v).simplify(n) } catch (_: Exception) { return n }
            return if (equivalent(n, s2)) s2 else n
        }

        // -------------------------------------------------------------- rewriting of 0·∞, ∞−∞ and indeterminate powers

        private fun rank(n: Node): Int = when {
            n is Node.Func -> 0
            n is Node.Trig && n.name.startsWith("arc") -> 1
            n is Node.Trig -> 3
            n is Node.Power && v in n.exp.variableNames() -> 4
            else -> 2
        }

        private fun forms(f: Node, c: Ctx, form: String): LV? {
            when {
                form == "0·∞" -> return zeroTimesInf(f, c)
                form == "∞−∞" && f is Node.Sum -> return infMinusInf(f, c)
                f is Node.Power && form in setOf("1^∞", "0^0", "∞^0") -> {
                    val u = f.base
                    val wln = Node.Product(listOf(f.exp, Node.Func("ln", null, u)))
                    step("${DTex.tex(f)}=e^{${DTex.tex(wln)}}", "Indeterminate power $form: write u^w = e^(w ln u) and find the limit of the exponent")
                    val inner = limit(wln, c.next())
                    return expOf(inner).also { step("e^{${valueTex(inner).substringBefore("\\approx").substringBefore("=")}}=${valueTex(it)}", "The limit of the power") }
                }
            }
            return null
        }

        private fun zeroTimesInf(f: Node, c: Ctx): LV? {
            val factors = if (f is Node.Product) f.factors else return null
            val ex = Ext(v, pt, side)
            val zero = ArrayList<Node>(); val inf = ArrayList<Node>(); val rest = ArrayList<Node>()
            for (x in factors) {
                when (val e = try { ex.eval(x) } catch (_: Exception) { return null }) {
                    is EF -> if (e.isZero) zero += x else rest += x
                    is EInf -> inf += x
                    EBounded -> return null
                }
            }
            if (zero.isEmpty() || inf.isEmpty()) return null
            fun prod(l: List<Node>) = if (l.size == 1) l[0] else Node.Product(l)
            val zr = zero.minOf { rank(it) }
            val ir = inf.minOf { rank(it) }
            val (numG, denG) = if (zr <= ir) zero to inf else inf to zero
            val numNode = if (rest.isEmpty()) prod(numG) else Node.Product(rest + numG)
            val denNode = simplified(Node.Quotient(NB.ONE, prod(denG)))
            val g = Node.Quotient(numNode, denNode)
            step(DTex.tex(g), "0·∞ form: move ${plainOf(DTex.tex(prod(denG)))} to the denominator as 1/${plainOf(DTex.tex(prod(denG)))} to get a quotient")
            return limit(g, c.next())
        }

        private fun infMinusInf(f: Node.Sum, c: Ctx): LV? {
            val rf = ratOf(f)
            val g: Node
            if (rf != null && rf.variables.all { it == v }) {
                g = parseNode(Printer.ratFn(rf.simplified().value))
            } else {
                val nums = ArrayList<Node>(); val dens = ArrayList<Node>()
                for (t in f.terms) {
                    var neg = false; var x = t
                    if (x is Node.Neg) { neg = true; x = x.arg }
                    if (x is Node.Quotient) { nums += if (neg) NB.neg(x.num) else x.num; dens += x.den } else { nums += if (neg) NB.neg(x) else x; dens += NB.ONE }
                }
                if (dens.all { NB.isOne(it) }) return null
                val total = dens.fold(NB.ONE as Node) { acc, d -> NB.mul(acc, d) }
                val parts = nums.indices.map { i -> NB.mulAll(listOf(nums[i]) + dens.filterIndexed { j, _ -> j != i }) }
                g = simplified(Node.Quotient(NB.add(parts), total))
            }
            step(DTex.tex(g), "∞−∞ form: combine into a single fraction")
            return limit(g, c.next())
        }

        // -------------------------------------------------------------- method 6: L'Hôpital

        private fun derive(n: Node): Pair<Node, Differentiator> {
            val dz = Differentiator(v)
            val raw = dz.d(n)
            val simp = try { Alg(v).simplify(raw) } catch (_: Exception) { raw }
            if (DerivCheck.run(n, raw, v).mismatch != null) throw UnsupportedMath("a derivative the solver could not verify numerically")
            val chosen = if (simp !== raw && DerivCheck.run(n, simp, v).mismatch == null) simp else raw
            return chosen to dz
        }

        private fun primeOf(n: Node) = if (n is Node.Variable || n is Node.Number) "${DTex.tex(n)}'" else "(${DTex.tex(n)})'"

        private fun lhopital(f: Node, c: Ctx): LV? {
            if (c.lh >= 3) return null
            val q = f as? Node.Quotient ?: return null
            val ex = Ext(v, pt, side)
            val en = try { ex.eval(q.num) } catch (_: Exception) { return null }
            val ed = try { ex.eval(q.den) } catch (_: Exception) { return null }
            val zz = en is EF && en.isZero && ed is EF && ed.isZero
            val ii = en is EInf && ed is EInf
            if (!zz && !ii) return null
            val round = c.lh + 1
            step("${DTex.tex(f)}\\to ${if (zz) "\\frac{0}{0}" else "\\frac{\\pm\\infty}{\\pm\\infty}"}", "L'Hôpital: ${if (zz) "0/0" else "∞/∞"} form (round $round of at most 3)")
            val (dn, zn) = derive(q.num)
            val (dd, zd) = derive(q.den)
            if (dd is Node.Number && dd.value.isZero) return null
            val rules = (zn.rules + zd.rules).distinct().joinToString(", ").ifEmpty { "Power rule" }
            step("\\frac{${primeOf(q.num)}}{${primeOf(q.den)}}=\\frac{${DTex.tex(dn)}}{${DTex.tex(dd)}}", "Differentiate numerator and denominator separately ($rules)")
            val raw = Node.Quotient(dn, dd)
            val simp = simplified(raw)
            fun formOf(n: Node): String? = try { Ext(v, pt, side).top(n); null } catch (i: Indet) { i.form } catch (_: Exception) { "?" }
            val fr = formOf(raw)
            val fs = formOf(simp)
            val g = if (simp !== raw && DTex.tex(simp) != DTex.tex(raw) && (fs == null || (fs == fr && DTex.tex(simp).length <= DTex.tex(raw).length))) simp else raw
            if (g !== raw) step(DTex.tex(g), "Simplify")
            val r = limit(g, c.next(lh = c.lh + 1))
            if (r is LOsc) throw UnsupportedMath("a limit where L'Hôpital's rule is inconclusive (the quotient of derivatives keeps oscillating)")
            return r
        }
    }
}

/** The safety check: the answer is compared with the function at points approaching the limit, in high precision where possible. */
internal object LimitCheck {
    private fun hasOnlyBig(n: Node) = !n.any { it is Node.Func || it is Node.Trig || (it is Node.Power && it.exp.variableNames().isNotEmpty()) }

    private fun xsFinite(pt: Pt, side: Int, ks: IntRange): List<Pair<Double, BigDecimal>> = ks.map { k ->
        val h = BigDecimal.ONE.movePointLeft(k)
        val big = pt.exact!!.toBigDecimal().let { if (side > 0) it.add(h) else it.subtract(h) }
        (pt.d + side * Math.pow(10.0, -k.toDouble())) to big
    }

    private fun xsInf(pt: Pt, ks: IntRange): List<Pair<Double, BigDecimal>> = ks.map { k ->
        (pt.inf * Math.pow(10.0, k.toDouble())) to BigDecimal.ONE.movePointRight(k).let { if (pt.inf < 0) it.negate() else it }
    }

    /** Doubles overflow quickly (e^x), so the sequence also has small steps: 2, 5, 10, 20, 50 … 10^6. */
    private fun xsInfDouble(pt: Pt): List<Double> = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1e3, 1e4, 1e5, 1e6).map { it * pt.inf }

    /** Null when the answer agrees with the numbers; otherwise why not. */
    fun check(f: Node, v: Char, pt: Pt, sides: List<Int>, lv: LV): String? {
        for (side in sides) {
            val reason = checkSide(f, v, pt, side, lv)
            if (reason != null) return reason
        }
        return null
    }

    private fun checkSide(f: Node, v: Char, pt: Pt, side: Int, lv: LV): String? {
        val where = if (pt.isInf) "x = ${if (pt.inf > 0) "" else "-"}10^3 … 10^12" else "points ${if (side > 0) "right" else "left"} of the limit point"
        if (lv is LOsc) return checkOsc(f, v, pt, side)
        val big = hasOnlyBig(f)
        val bigXs = if (pt.isInf) xsInf(pt, 3..12) else xsFinite(pt, side, 3..10)
        val dblXs = if (pt.isInf) xsInfDouble(pt).map { it to BigDecimal.ZERO } else xsFinite(pt, side, 2..6)
        val bigVals: List<BigDecimal>? = if (big) {
            val vals = bigXs.map { NodeBig.eval(f, it.second, v) }
            if (vals.count { it != null } >= 6) vals.filterNotNull() else null
        } else null
        val dbl: List<Double> = dblXs.mapNotNull { x ->
            try { NodeNum.eval(f, mapOf(v to x.first)).takeIf { !it.isNaN() && !it.isInfinite() } } catch (_: Exception) { null }
        }
        when (lv) {
            is LFin -> {
                val l = lv.d
                if (bigVals != null) {
                    val err = bigVals.last().subtract(BigDecimal(l)).abs().toDouble() / (1 + Math.abs(l))
                    // the exact value is compared in high precision when there is one
                    val err2 = if (lv.exact != null) bigVals.last().subtract(lv.exact.toBigDecimal()).abs().toDouble() / (1 + Math.abs(l)) else err
                    if (err2 <= 1e-5) return null
                    return "the function at $where gives ${Printer.num(bigVals.last().toDouble(), 6)}, not ${Printer.num(l, 6)}"
                }
                if (dbl.size < 2) return "the function could not be evaluated near the limit point ($where)"
                val tail = dbl.takeLast(3).map { Math.abs(it - l) / (1 + Math.abs(l)) }
                if (tail.min() <= 3e-3 && Math.abs(dbl.last() - l) / (1 + Math.abs(l)) <= 5e-2) return null
                // slow convergence (ln x/√x): the error must keep shrinking by at least ~half per decade, so a wrong value can't hide behind it
                val decades = (if (pt.isInf) xsInf(pt, 3..6) else xsFinite(pt, side, 3..6)).map { x ->
                    try { NodeNum.eval(f, mapOf(v to x.first)) } catch (_: Exception) { Double.NaN }
                }
                val errs = decades.map { Math.abs(it - l) }
                if (errs.none { it.isNaN() } && (0 until errs.size - 1).all { errs[it + 1] <= 0.55 * errs[it] } && errs.last() <= 0.05 * (1 + Math.abs(l))) return null
                return "the function at $where gives ${Printer.num(dbl.last(), 6)}, not ${Printer.num(l, 6)}"
            }
            is LInf -> {
                val vals: List<Double> = bigVals?.map { it.toDouble() }?.filter { !it.isInfinite() } ?: dbl
                if (vals.size < 3) return "the function could not be evaluated near the limit point ($where)"
                if (vals.any { it * lv.sign <= 0 }) return "the function at $where has the wrong sign for ${if (lv.sign > 0) "+∞" else "-∞"}"
                val mags = vals.map { Math.abs(it) }
                for (i in 1 until mags.size) if (mags[i] < mags[i - 1] * (1 - 1e-9)) return "the function at $where does not keep growing"
                if (mags.last() < 2 * mags.first() || mags.last() < 5) return "the function at $where does not grow without bound (last value ${Printer.num(vals.last(), 4)})"
                return null
            }
            LOsc -> return null
        }
    }

    private fun checkOsc(f: Node, v: Char, pt: Pt, side: Int): String? {
        val vals = (0 until 300).mapNotNull { k ->
            val x = if (pt.isInf) pt.inf * 1e3 * (1 + 0.0307 * k) else pt.d + side * 1e-4 * (1 + 0.0307 * k)
            try { NodeNum.eval(f, mapOf(v to x)) } catch (_: Exception) { null }
        }
        if (vals.size < 50) return "the function could not be evaluated near the limit point"
        return if (vals.max() - vals.min() >= 1.0) null else "the function does not oscillate where expected"
    }

    /** A limit whose answer still has other letters: compared at two values of them. */
    fun checkParam(f: Node, v: Char, pt: Pt, claim: Node): String? {
        val others = f.variableNames() - v
        for (base in listOf(1.3, -0.7)) {
            val env = HashMap<Char, Double>()
            for ((i, c) in others.withIndex()) env[c] = base + 0.4 * i
            val want = try { NodeNum.eval(claim, env) } catch (_: Exception) { return "the answer could not be evaluated" }
            val bigEnv = env.mapValues { BigDecimal(it.value) }
            var last: Double? = null
            for (k in 6..12 step 3) {
                val x = BigDecimal.ONE.movePointLeft(k)
                last = NodeBig.eval(f, x, v, bigEnv)?.toDouble()
            }
            val got = last ?: try { NodeNum.eval(f, env + (v to 1e-5)) } catch (_: Exception) { return "the function could not be evaluated" }
            if (Math.abs(got - want) > 1e-4 * (1 + Math.abs(want))) return "the function near ${v} = 0 gives ${Printer.num(got, 6)}, not ${Printer.num(want, 6)}"
        }
        return null
    }
}
