package dev.ilgax.utils.math.solver

/** A logarithm whose argument or base contains an unknown. */
internal fun Node.hasLogVar(): Boolean = any { it is Node.Func && (it.arg.hasVariable() || it.base?.hasVariable() == true) }

/** A logarithm whose base contains an unknown. */
internal fun Node.hasUnknownBase(): Boolean = any { it is Node.Func && it.base?.hasVariable() == true }

/**
 * Identities for logarithms applied to the parsed tree before it is evaluated: change-of-base chains
 * (`log_a b · log_b c = log_a c`), logarithms of roots, of powers with an unknown exponent and of products of those
 * (`ln √u = ½ ln u`, `log_b a^g = g·log_b a`), and the outermost logarithm of a nested one (`log_b A = c` means
 * `A = b^c`). Every rewrite leaves a step behind; the original equation is still what candidates are checked against.
 */
internal class LogRewrites(private val s: SolveSession) {
    /** True once a change-of-base chain was simplified. */
    var chained = false
        private set

    /** A node (base or argument) that cancelled out of a chain: it must still be > 0, and ≠ 1 when it served as a base. */
    class Restriction(val node: Node, val notOne: Boolean)

    val restrictions = ArrayList<Restriction>()

    private val chainSteps = ArrayList<Step>()
    private val expandRules = LinkedHashSet<String>()

    fun apply(lines: List<ParsedLine>): List<ParsedLine> {
        if (lines.none { l -> l.parts.any { it.hasFunc() } }) return lines
        val out = lines.map { line ->
            var changed = false
            val parts = line.parts.map { p ->
                val q = expand(chain(p))
                if (q != p) changed = true
                q
            }
            if (!changed) line else ParsedLine(parts, line.parts.indices.map { i -> if (parts[i] != line.parts[i]) NodeTex.tex(parts[i]) else line.sources[i] }, line.ops)
        }
        for (st in chainSteps) s.step(st.latex, st.caption)
        if (expandRules.isNotEmpty()) {
            val line = out.firstOrNull { l -> l.parts.any { it.hasLogVar() } } ?: out[0]
            s.step(joinLine(line), expandRules.joinToString("; "))
        }
        return out.map { peel(it) }
    }

    private fun joinLine(line: ParsedLine): String {
        val sb = StringBuilder(NodeTex.tex(line.parts[0]))
        for (i in line.ops.indices) {
            sb.append(when (line.ops[i]) { "<=" -> "\\le"; ">=" -> "\\ge"; else -> line.ops[i] }).append(NodeTex.tex(line.parts[i + 1]))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ constants

    private fun constSurd(n: Node): Surd? = if (n.hasVariable()) null else try { NodeExact.eval(n, emptyMap()) } catch (_: Exception) { null }

    private fun constPositive(n: Node): Boolean = !n.hasVariable() && try { NodeNum.eval(n, emptyMap()) > 0 } catch (_: Exception) { false }

    private fun num(r: Rational): Node = if (r.signum < 0) Node.Neg(Node.Number(-r)) else Node.Number(r)

    // ------------------------------------------------------------------ change of base chains

    private class Flat {
        var coef = Rational.ONE
        val others = ArrayList<Node>()
        val otherDen = ArrayList<Node>()
        val logs = ArrayList<Node.Func>()
        val logsDen = ArrayList<Node.Func>()
    }

    private fun flatten(n: Node, inv: Boolean, f: Flat): Boolean {
        when (n) {
            is Node.Product -> for (x in n.factors) if (!flatten(x, inv, f)) return false
            is Node.Quotient -> { if (!flatten(n.num, inv, f) || !flatten(n.den, !inv, f)) return false }
            is Node.Neg -> { f.coef = -f.coef; return flatten(n.arg, inv, f) }
            is Node.Number -> {
                if (n.value.isZero && inv) return false
                f.coef = if (inv) f.coef / n.value else f.coef * n.value
            }
            is Node.Func -> (if (inv) f.logsDen else f.logs) += n
            else -> (if (inv) f.otherDen else f.others) += n
        }
        return true
    }

    private fun baseOf(f: Node.Func): Node = if (f.name == "ln") Node.Const('e') else f.base!!

    private fun same(a: Node, b: Node): Boolean {
        if (a == b) return true
        val x = constSurd(a) ?: return false
        return x == constSurd(b)
    }

    private fun mk(base: Node, arg: Node): Node.Func =
        if (base == Node.Const('e')) Node.Func("ln", null, arg) else Node.Func("log", base, arg)

    private fun lnTex(n: Node) = NodeTex.tex(Node.Func("ln", null, n))

    private fun ratioTex(num: List<Node>, den: List<Node>): String {
        fun side(xs: List<Node>) = xs.filter { it != Node.Const('e') }.joinToString("\\cdot ") { lnTex(it) }.ifEmpty { "1" }
        val d = side(den)
        return if (d == "1") side(num) else "\\frac{${side(num)}}{$d}"
    }

    private fun chain(root: Node): Node = root.rewrite { n ->
        if (n is Node.Product || n is Node.Quotient) tryChain(n) else null
    }

    private fun tryChain(n: Node): Node? {
        val f = Flat()
        if (!flatten(n, false, f)) return null
        // logs that are exact numbers are plain coefficients
        val keepNum = ArrayList<Node.Func>()
        val keepDen = ArrayList<Node.Func>()
        for ((list, inv) in listOf(f.logs to false, f.logsDen to true)) for (lg in list) {
            val v = constSurd(lg)
            val r = v?.takeIf { it.isRational && !(inv && it.isZero) }?.rational
            if (r != null) f.coef = if (inv) f.coef / r else f.coef * r else (if (inv) keepDen else keepNum) += lg
        }
        if (keepNum.size + keepDen.size < 2) return null
        // an invalid constant base or argument is the evaluator's to report
        for (lg in keepNum + keepDen) {
            if (!lg.arg.hasVariable() && !constPositive(lg.arg)) return null
            val b = baseOf(lg)
            if (!b.hasVariable() && (!constPositive(b) || constSurd(b) == Surd.ONE)) return null
        }
        val top = ArrayList<Node>()
        val bottom = ArrayList<Node>()
        val topBase = ArrayList<Boolean>()
        val botBase = ArrayList<Boolean>()
        for (lg in keepNum) { top += lg.arg; topBase += false; bottom += baseOf(lg); botBase += true }
        for (lg in keepDen) { top += baseOf(lg); topBase += true; bottom += lg.arg; botBase += false }
        val before = ratioTex(top, bottom)
        val t = ArrayList(top)
        val b = ArrayList(bottom)
        var cancelled = false
        val gone = ArrayList<Node>()
        var i = 0
        while (i < t.size) {
            val j = b.indexOfFirst { same(t[i], it) }
            if (j >= 0) {
                gone += t[i]
                if (t[i].hasVariable()) restrictions += Restriction(t[i], topBase[i] || botBase[j])
                t.removeAt(i); b.removeAt(j); topBase.removeAt(i); botBase.removeAt(j)
                cancelled = true
            } else i++
        }
        if (!cancelled) return null
        val pairs = t.indices.map { mk(b[it], t[it]) }
        val shownGone = gone.filter { it != Node.Const('e') }
        chained = true
        chainSteps += Step(before, "Change of base to natural logarithms: log_b a = ln a / ln b")
        if (shownGone.isNotEmpty()) chainSteps += Step(ratioTex(t, b), "Cancel " + shownGone.joinToString(", ") { plainOf(lnTex(it)) })
        val shownLogs = pairs.joinToString("\\cdot ") { NodeTex.tex(it) }.ifEmpty { "1" }
        if (pairs.isNotEmpty()) chainSteps += Step(shownLogs, "Change of base back: ln a / ln b = log_b a")
        // exact values of what remains
        val rest = ArrayList<Node>()
        for (p in pairs) {
            val v = constSurd(p)
            if (v != null && v.isRational) f.coef *= v.rational else rest += p
        }
        val topFactors = ArrayList<Node>()
        if (f.coef.abs() != Rational.ONE || (rest.isEmpty() && f.others.isEmpty())) topFactors += Node.Number(f.coef.abs())
        topFactors += f.others
        topFactors += rest
        var out: Node = if (topFactors.size == 1) topFactors[0] else Node.Product(topFactors)
        if (f.otherDen.isNotEmpty()) out = Node.Quotient(out, if (f.otherDen.size == 1) f.otherDen[0] else Node.Product(f.otherDen))
        return if (f.coef.signum < 0) Node.Neg(out) else out
    }

    // ------------------------------------------------------------------ logs of roots, powers and products

    private fun fold(head: Node.Func, a: Node): Node {
        val b = if (head.name == "ln") Surd.E_SURD else constSurd(head.base!!)
        val v = constSurd(a)
        if (b != null && v != null && v.signum() > 0 && b.signum() > 0) NumberTheory.exactLog(b, v)?.let { return num(it) }
        return Node.Func(head.name, head.base, a)
    }

    private fun times(e: Node, x: Node): Node {
        if (x is Node.Number && x.value == Rational.ONE) return e
        val es = if (e is Node.Product) e.factors else listOf(e)
        return Node.Product(if (x is Node.Number || x is Node.Neg) listOf(x) + es else es + x)
    }

    private fun safe(a: Node, top: Boolean): Boolean = when {
        !a.hasVariable() -> constPositive(a)
        a is Node.Power -> !a.base.hasVariable() && a.exp.hasVariable() && constPositive(a.base)
        a is Node.Root -> (a.index % 2 == 0 || top) && a.arg.hasVariable()
        a is Node.Product -> a.factors.all { safe(it, false) }
        a is Node.Quotient -> safe(a.num, false) && safe(a.den, false)
        else -> false
    }

    private fun split(h: Node.Func, a: Node): Node = when {
        !a.hasVariable() -> fold(h, a)
        a is Node.Power -> { expandRules += "Power rule: log_b(aᵍ) = g·log_b a"; times(a.exp, fold(h, a.base)) }
        a is Node.Root -> {
            expandRules += "Root: log √u = ½·log u"
            val inner = if (safe(a.arg, false)) split(h, a.arg) else Node.Func(h.name, h.base, a.arg)
            Node.Product(listOf(Node.Number(Rational.of(1, a.index.toLong())), inner))
        }
        a is Node.Product -> { expandRules += "Product rule: log (ab) = log a + log b (both factors are positive)"; Node.Sum(a.factors.map { split(h, it) }) }
        a is Node.Quotient -> { expandRules += "Quotient rule: log (a/b) = log a − log b (both parts are positive)"; Node.Sum(listOf(split(h, a.num), Node.Neg(split(h, a.den)))) }
        else -> Node.Func(h.name, h.base, a)
    }

    private fun expand(root: Node): Node = root.rewrite { n ->
        if (n is Node.Func && n.arg.hasVariable() && n.base?.hasVariable() != true) {
            val arg = expand(n.arg)
            if (safe(arg, true)) split(Node.Func(n.name, n.base, arg), arg) else if (arg != n.arg) Node.Func(n.name, n.base, arg) else n
        } else null
    }

    // ------------------------------------------------------------------ nested logarithms

    private fun nonPoly(a: Node) = a.any { it is Node.Func || it is Node.Root || (it is Node.Power && it.exp.hasVariable()) }

    private fun peel(line: ParsedLine): ParsedLine {
        if (line.parts.size != 2 || !line.isEquation) return line
        var l = line.parts[0]
        var r = line.parts[1]
        var guard = 0
        while (guard++ < 8) {
            val leftIsLog = l is Node.Func && l.arg.hasVariable() && l.base?.hasVariable() != true && nonPoly(l.arg) && !r.hasVariable()
            val rightIsLog = r is Node.Func && r.arg.hasVariable() && r.base?.hasVariable() != true && nonPoly(r.arg) && !l.hasVariable()
            if (!leftIsLog && !rightIsLog) break
            val f = (if (leftIsLog) l else r) as Node.Func
            val c = if (leftIsLog) r else l
            val cv = constSurd(c)
            if (cv == null || !cv.isRational || !cv.rational.isInteger) throw UnsupportedMath("a logarithm of a logarithm (or of a root or power of the unknown) whose outer value is not a whole number")
            val base = baseOf(f)
            val exp: Node = num(cv.rational)
            val nl = f.arg
            val nr = Node.Power(base, exp)
            s.step("${NodeTex.tex(nl)}=${NodeTex.tex(nr)}", "Undo the outer logarithm: log_b A = c means A = bᶜ (A > 0 holds automatically)")
            l = nl
            r = nr
        }
        return if (l === line.parts[0] && r === line.parts[1]) line else ParsedLine(listOf(l, r), listOf(NodeTex.tex(l), NodeTex.tex(r)), line.ops)
    }
}
