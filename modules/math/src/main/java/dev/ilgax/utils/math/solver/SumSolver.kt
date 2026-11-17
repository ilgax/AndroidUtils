package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Test hook: replaces the value the closed form gives, to prove that the numeric safety check catches a wrong answer. */
internal object SumHooks {
    @Volatile var tamper: ((Rational) -> Rational)? = null
}

/** The numeric safety check disagreed with the closed form. */
internal class SumUnreliable(val why: String) : Exception(why)

/**
 * Finite sums `\sum_{k=a}^{b} f(k)`. The summand is read as a sum of c·k^p·r^k ([KForm]); each piece is summed with a
 * formula (Faulhaber through Bernoulli numbers for k^p, the geometric formula for r^k), the pieces are added and the
 * result is factored. Bounds are whole numbers or linear in one letter (n, 2n, n+1). Every closed form is compared with
 * direct summation (numeric bounds: the sum itself; symbolic bounds: n = lower bound … lower bound + 6) before it is shown.
 * Other summands are added term by term when the bounds are numbers and the count is at most 10000.
 */
internal class SumSolver(private val s: SolveSession) {
    companion object {
        const val MAX_DIRECT = 10_000
        private const val NOT_CLOSED = "a closed form for this sum isn't supported; infinite sums come later"
        private val infinite = Regex("""^\+?\\infty$|^\+?∞$""")
    }

    private class Spec(val idx: Char, val loTex: String, val hiTex: String, val body: String)

    // ------------------------------------------------------------------ notation

    private fun clean(latex: String): String {
        var t = compactTex(latex).replace("\\limits", "").replace("\\displaystyle", "")
        t = Regex("""\\sum_\{([A-Za-z])=(-?\w+)\^""").replace(t) { "\\sum_{${it.groupValues[1]}=${it.groupValues[2]}}^" }
        t = Regex("""\\sum_([A-Za-z])=(-?\w+)\^""").replace(t) { "\\sum_{${it.groupValues[1]}=${it.groupValues[2]}}^" }
        val missing = t.count { it == '{' } - t.count { it == '}' }
        if (missing > 0) t += "}".repeat(missing)
        return t
    }

    private fun closing(t: String, open: Int): Int {
        var d = 0
        for (i in open until t.length) {
            if (t[i] == '{') d++
            if (t[i] == '}') { d--; if (d == 0) return i }
        }
        return -1
    }

    /** The argument at [i]: a braced group, a command, a digit run or one character. Returns the text and the index after it. */
    private fun group(t: String, i: Int): Pair<String, Int> {
        if (i >= t.length) throw SolveFailure("the sum is missing a bound")
        if (t[i] == '{') {
            val c = closing(t, i)
            if (c < 0) throw SolveFailure("the braces of the sum are not closed")
            return t.substring(i + 1, c) to c + 1
        }
        var j = i
        if (t[j] == '\\') { j++; while (j < t.length && t[j].isLetter()) j++ }
        else if (t[j] == '+' || t[j] == '-') { j++; while (j < t.length && t[j].isDigit()) j++ }
        else if (t[j].isDigit()) { while (j < t.length && t[j].isDigit()) j++ }
        else j++
        return t.substring(i, j) to j
    }

    private fun topLevelEq(t: String): List<Int> {
        var d = 0
        val at = ArrayList<Int>()
        for ((i, c) in t.withIndex()) {
            if (c == '{' || c == '(' || c == '[') d++
            if (c == '}' || c == ')' || c == ']') d--
            if (c == '=' && d == 0) at += i
        }
        return at
    }

    private fun readSpec(t: String): Spec {
        if (!t.startsWith("\\sum")) throw UnsupportedMath("a sum inside a larger expression")
        var i = 4
        var sub: String? = null
        var sup: String? = null
        repeat(2) {
            if (i < t.length && t[i] == '_' && sub == null) { val (g, j) = group(t, i + 1); sub = g; i = j }
            else if (i < t.length && t[i] == '^' && sup == null) { val (g, j) = group(t, i + 1); sup = g; i = j }
        }
        val sb = sub ?: throw SolveFailure("the sum needs “k=1” under it, like \\sum_{k=1}^{n}")
        val hi = sup ?: throw SolveFailure("the sum needs an upper bound above it, like \\sum_{k=1}^{n}")
        val eq = sb.indexOf('=')
        if (eq != 1 || !sb[0].isLetter()) throw SolveFailure("the sum needs the index and its start under it, like k=1")
        val lo = sb.substring(2)
        if (lo.isEmpty()) throw SolveFailure("the sum needs a start value, like k=1")
        var body = t.substring(i)
        if (body.startsWith("{") && closing(body, 0) == body.length - 1) body = body.substring(1, body.length - 1)
        if (body.isEmpty()) throw SolveFailure("there is nothing after the sum sign to add up")
        if (body.contains("\\sum")) throw UnsupportedMath("a sum inside a sum")
        return Spec(sb[0], lo, hi, body)
    }

    private fun parseNode(src: String): Node {
        val lines = LatexParser.parse(src, null, AngleMode.Radians)
        if (lines.size != 1 || lines[0].parts.size != 1) throw UnsupportedMath("an equation or several expressions where one expression is expected")
        return lines[0].parts[0]
    }

    // ------------------------------------------------------------------ entry

    fun solve(latex: String): SolveResult {
        return try { run(latex) } catch (u: Undefined) { SolveResult.Failed(u.why) }
    }

    private fun run(latex: String): SolveResult {
        val t = clean(latex)
        if (t.contains("\\\\")) throw UnsupportedMath("several lines with a sum")
        val eqs = topLevelEq(t)
        if (eqs.size > 1) throw UnsupportedMath("a chain of equalities with a sum")
        var sumText = t
        var rhsText: String? = null
        if (eqs.isNotEmpty()) {
            val l = t.substring(0, eqs[0])
            val r = t.substring(eqs[0] + 1)
            when {
                l.contains("\\sum") && r.contains("\\sum") -> throw UnsupportedMath("sums on both sides of an equation")
                l.contains("\\sum") -> { sumText = l; rhsText = r.ifEmpty { null } }
                r.contains("\\sum") -> { sumText = r; rhsText = l.ifEmpty { null } }
                else -> throw UnsupportedMath("an equation without a sum")
            }
        }
        val spec = readSpec(sumText)
        if (infinite.matches(spec.hiTex)) {
            return SolveResult.Failed("infinite series (an upper bound of ∞) aren't supported yet; they are planned, together with convergence", null)
        }
        val idx = spec.idx
        val loNode = parseNode(spec.loTex)
        val hiNode = parseNode(spec.hiTex)
        val boundLetters = (loNode.variableNames() + hiNode.variableNames()).toSortedSet()
        if (boundLetters.size > 1) throw UnsupportedMath("bounds with more than one letter")
        val bv: Char? = boundLetters.firstOrNull()
        if (bv == idx) throw SolveFailure("the index letter $idx is also used in a bound")
        val lo = affine(loNode, bv)
        val hi = affine(hiNode, bv)
        val sym = bv != null
        val v = (bv ?: 'n').toString()

        // the summand; one other letter that only scales it (c) is a constant factor
        var bodyNode = parseNode(spec.body)
        val letters = bodyNode.variableNames() - idx
        var param: Char? = null
        if (letters.isNotEmpty()) {
            if (bv != null && bv in letters) throw UnsupportedMath("the letter $bv in the summand as well as in a bound")
            if (letters.size > 1) throw UnsupportedMath("a summand with several letters besides the index")
            param = letters.first()
            val c = param
            fun at(k: Long, cv: Long) = RatEval.eval(bodyNode, mapOf(idx to Rational.of(k), c to Rational.of(cv)))
            try {
                for (k in -3L..6L) {
                    val one = at(k, 1)
                    if (!at(k, 0).isZero || at(k, 2) != one * Rational.of(2) || at(k, 3) != one * Rational.of(3)) throw UnsupportedMath("a summand that is not just $c times a function of $idx")
                }
            } catch (_: RatEval.NotRational) { throw UnsupportedMath("a summand with the letter $c that this solver can't take apart") }
            bodyNode = bodyNode.rewrite { if (it is Node.Variable && it.name == c) Node.Number(Rational.ONE) else null }
        }
        val rhs: Rational? = rhsText?.let { r ->
            if (!sym) throw UnsupportedMath("an equation with a sum whose bounds are both numbers (nothing to solve for)")
            if (param != null) throw UnsupportedMath("an equation with a constant letter in the summand")
            try { RatEval.eval(parseNode(r), emptyMap()) } catch (_: RatEval.NotRational) { throw UnsupportedMath("a right side that isn't a number") }
        }

        val bodyShown = NodeTex.tex(bodyNode).let { if (bodyNode is Node.Sum || (bodyNode is Node.Neg)) "($it)" else it }
        val leadTex = param?.toString()
        val head = "\\sum_{$idx=${spec.loTex}}^{${spec.hiTex}}"
        val whole = head + (if (param != null) param.toString() else bodyShown)
        s.step(whole, "The sum" + if (rhs != null) " (set equal to ${plainOf(Printer.rational(rhs))})" else "")

        fun fAt(k: Long): Rational = RatEval.eval(bodyNode, mapOf(idx to Rational.of(k)))

        val form = try { KForm.of(bodyNode, idx) } catch (e: NotClosed) { null } catch (e: ArithmeticException) { throw SolveFailure("the summand divides by zero") }
        val closed = form?.let { SumFormula.closed(it, lo.poly, hi.poly) }

        if (!sym) {
            val L = lo.value()
            val H = hi.value()
            if (H < L) {
                s.note("Empty sum: the upper bound is below the lower bound, so there are no terms and the sum is 0")
                s.step("$whole=0", "The upper bound is below the lower bound, so there are no terms")
                return numericAnswer(whole, Rational.ZERO, leadTex)
            }
            if (closed == null || form == null) return fallback(whole, spec, idx, L, H, ::fAt, leadTex)
            describe(form, spec, idx, v, lo, hi, closed, leadTex, whole, bodyShown, false)
            val value = SumHooks.tamper?.invoke(closed.value()) ?: closed.value()
            verifyNumeric(form, L, H, value, ::fAt)
            return numericAnswer(whole, value, leadTex)
        }

        if (closed == null || form == null) throw SolveFailure(NOT_CLOSED)
        val thr = threshold(lo, hi)
        if (thr != null) s.note("The closed form holds for whole numbers $v ≥ $thr (a sum with no terms is 0)")
        val formatted = describe(form, spec, idx, v, lo, hi, closed, leadTex, whole, bodyShown, true)
        verifySymbolic(closed, lo, hi, ::fAt)
        if (rhs == null) {
            val ans = Answer("$whole=${formatted.tex}", formatted.plain)
            return SolveResult.Solved(listOf(ans), s.steps, s.notes)
        }
        return equation(idx, v, closed, lo, hi, rhs, ::fAt, bodyShown, formatted)
    }

    // ------------------------------------------------------------------ bounds

    private class Aff(val poly: UPoly) {
        fun value(): Long = poly.coeff(0).num.toLong()
        fun eval(n: Long): Long = poly.eval(Rational.of(n)).num.toLong()
        val a get() = poly.coeff(1)
        val b get() = poly.coeff(0)
    }

    private fun affine(n: Node, v: Char?): Aff {
        val f = try { KForm.of(n, v ?: 'n') } catch (e: NotClosed) { throw UnsupportedMath("a bound that isn't a whole number or linear in one letter") }
        val (a, b) = f.affine() ?: throw UnsupportedMath("a bound that isn't a whole number or linear in one letter")
        if (!a.isInteger || !b.isInteger) throw UnsupportedMath("a bound that isn't a whole number or a whole-number multiple of the letter plus a whole number")
        if (a.num.bitLength() > 30 || b.num.bitLength() > 30) throw UnsupportedMath("a bound that large")
        return Aff(UPoly.of(listOf(b, a)))
    }

    /** Smallest n with hi(n) ≥ lo(n) − 1 (the closed form is exact from there on), when it grows with n. */
    private fun threshold(lo: Aff, hi: Aff): Long? {
        val d = hi.a - lo.a
        if (d.signum <= 0) return null
        val q = (lo.b - Rational.ONE - hi.b) / d   // n ≥ q
        return BigDecimal(q.num).divide(BigDecimal(q.den), 0, RoundingMode.CEILING).toLong()
    }

    // ------------------------------------------------------------------ the steps

    private fun coefTex(c: Rational): String = when {
        c == Rational.ONE -> ""
        c == -Rational.ONE -> "-"
        c.isInteger -> c.num.toString()
        else -> Printer.rational(c)
    }

    private fun pieceBody(k: KKey, idx: Char): String = when {
        k.r != Rational.ONE -> (if (k.r.isInteger) (if (k.r.signum < 0) "(${k.r.num})" else k.r.num.toString()) else "(${Printer.rational(k.r)})") + "^{$idx}"
        k.p == 0 -> "1"
        k.p == 1 -> "$idx"
        else -> "$idx^{${k.p}}"
    }

    private fun joinPieces(parts: List<Fmt>): Fmt {
        val tex = StringBuilder()
        val plain = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            val neg = p.tex.startsWith("-")
            val bt = if (neg) p.tex.substring(1) else p.tex
            val bp = if (neg) p.plain.substring(1) else p.plain
            val wrap = needsParens(bp) && parts.size > 1
            val st = if (wrap) "($bt)" else bt
            val sp = if (wrap) "($bp)" else bp
            tex.append(if (neg) "-" else if (i == 0) "" else "+").append(st)
            plain.append(if (neg) "-" else if (i == 0) "" else "+").append(sp)
        }
        return Fmt(tex.toString(), plain.toString())
    }

    private fun needsParens(plain: String): Boolean {
        var d = 0
        for ((i, ch) in plain.withIndex()) {
            if (ch == '(') d++ else if (ch == ')') d--
            else if (d == 0 && i > 0 && (ch == '+' || ch == '-') && plain[i - 1] != '^') return true
        }
        return false
    }

    private fun numFmt(r: Rational, lead: String?): Fmt {
        val b = Fmt.rat(r)
        if (lead == null) return b
        if (r == Rational.ONE) return Fmt.of(lead)
        if (r == -Rational.ONE) return Fmt.of("-$lead")
        return Fmt(b.tex + lead, b.plain + lead)
    }

    /** The formula captions, the split, the per-piece sums and the simplification. Returns the final value shown. */
    private fun describe(
        form: KForm, spec: Spec, idx: Char, v: String, lo: Aff, hi: Aff, closed: Closed, lead: String?, whole: String, bodyShown: String, sym: Boolean,
    ): Fmt {
        val head = "\\sum_{$idx=${spec.loTex}}^{${spec.hiTex}}"
        val pieces = form.t.entries.sortedWith(compareBy({ it.key.r != Rational.ONE }, { -it.key.p }, { it.key.r }))
        fun body(k: KKey, c: Rational) = coefTex(c) + head + pieceBody(k, idx)
        val split = pieces.joinToString("") { (k, c) ->
            val t = body(k, c)
            if (t.startsWith("-")) t else "+$t"
        }.removePrefix("+")
        val plainBody = pieces.size == 1 && pieces[0].value == Rational.ONE && bodyShown == pieceBody(pieces[0].key, idx)
        if (!plainBody || lead != null) s.step("$whole=${(lead ?: "")}${if (pieces.size > 1 && lead != null) "($split)" else split}", if (lead != null) "$lead is a constant, so it comes out of the sum" + (if (pieces.size > 1) "; the rest is split (linearity)" else "") else "Split the sum and pull the constants out (linearity)")
        val partFmts = ArrayList<Fmt>()
        for ((k, c) in pieces) {
            val cf = SumFormula.pieceClosed(k, c, lo.poly, hi.poly)!!
            val caption = formulaCaption(k, idx)
            val shown = if (sym) SeriesFmt.closed(cf, v) else Fmt.rat(cf.value())
            partFmts += shown
            s.step("${body(k, c)}=${shown.tex}", caption)
        }
        val total = if (sym) SeriesFmt.closed(closed, v, lead) else numFmt(closed.value(), lead)
        if (pieces.size > 1) {
            val added = joinPieces(partFmts)
            val addedLead = if (lead != null) Fmt("$lead(${added.tex})", "$lead(${added.plain})") else added
            s.step("$whole=${addedLead.tex}", "Add the pieces")
            if (addedLead.tex != total.tex) s.step("$whole=${total.tex}", "Simplify")
        } else if (lead != null) {
            s.step("$whole=${total.tex}", "Put the constant $lead back")
        }
        return total
    }

    private fun formulaCaption(k: KKey, idx: Char): String = when {
        k.r != Rational.ONE -> "Geometric sum with r = ${plainOf(Printer.rational(k.r))}: Σ r^$idx from a to b = (r^(b+1) − r^a)/(r − 1)"
        k.p == 0 -> "A constant summed over the range: Σ 1 = b − a + 1 (the number of terms)"
        else -> {
            val f = SeriesFmt.factored(Bernoulli.powerSum(k.p), "m").plain
            val via = if (k.p >= 4) ", from the Bernoulli numbers" else ""
            "Power sum$via: Σ ${if (k.p == 1) "$idx" else "$idx^${k.p}"} from 1 to m = $f, so from a to b it is S(b) − S(a−1)"
        }
    }

    // ------------------------------------------------------------------ the checks

    private fun verifyNumeric(form: KForm, L: Long, H: Long, value: Rational, fAt: (Long) -> Rational) {
        val count = H - L + 1
        if (count <= 100_000) {
            var d = Rational.ZERO
            for (k in L..H) d += fAt(k)
            if (d != value) throw SumUnreliable("adding the terms gives ${plainOf(Printer.rational(d))}, the formula gives ${plainOf(Printer.rational(value))}")
            return
        }
        // too many terms to add all of them: both ends of the range, each end in direct summation
        for ((a, b) in listOf(L to L + 6, H - 6 to H)) {
            val cf = SumFormula.closed(form, UPoly.const(Rational.of(a)), UPoly.const(Rational.of(b))) ?: continue
            var d = Rational.ZERO
            for (k in a..b) d += fAt(k)
            val got = SumHooks.tamper?.invoke(cf.value()) ?: cf.value()
            if (d != got) throw SumUnreliable("adding the terms $a … $b gives ${plainOf(Printer.rational(d))}, the formula gives ${plainOf(Printer.rational(got))}")
        }
    }

    private fun verifySymbolic(closed: Closed, lo: Aff, hi: Aff, fAt: (Long) -> Rational) {
        val start = if (lo.a.isZero) lo.value() else 0L
        var n = start
        var checked = 0
        while (checked < 7 && n < start + 60) {
            val a = lo.eval(n)
            val b = hi.eval(n)
            if (b >= a - 1) {
                if (b - a > 50_000) throw UnsupportedMath("a sum with that many terms")
                var d = Rational.ZERO
                for (k in a..b) d += fAt(k)
                val got = SumHooks.tamper?.invoke(closed.eval(n)) ?: closed.eval(n)
                if (d != got) throw SumUnreliable("at n = $n adding the terms gives ${plainOf(Printer.rational(d))}, the formula gives ${plainOf(Printer.rational(got))}")
                checked++
            }
            n++
        }
        if (checked == 0) throw SolveFailure("no whole number n makes the upper bound reach the lower bound")
    }

    // ------------------------------------------------------------------ answers

    private fun numericAnswer(whole: String, value: Rational, lead: String?): SolveResult {
        if (lead != null) {
            val f = numFmt(value, lead)
            return SolveResult.Solved(listOf(Answer("$whole=${f.tex}", f.plain)), s.steps, s.notes)
        }
        val a = s.valueAnswer(null, Val.of(Surd.of(value)))
        val line = "$whole=${a.latex}"
        if (s.steps.last().latex != line) s.step(line, "The value")
        return SolveResult.Solved(listOf(Answer(line, a.plain)), s.steps, s.notes)
    }

    // ------------------------------------------------------------------ the fallback: add the terms

    private fun fallback(whole: String, spec: Spec, idx: Char, L: Long, H: Long, fAt: (Long) -> Rational, lead: String?): SolveResult {
        val count = H - L + 1
        if (count > MAX_DIRECT) return SolveResult.Failed("$NOT_CLOSED (it has $count terms, more than $MAX_DIRECT)")
        var total = Rational.ZERO
        val terms = ArrayList<Rational>()
        try {
            for (k in L..H) { val t = fAt(k); terms += t; total += t }
        } catch (_: RatEval.NotRational) {
            return SolveResult.Failed(NOT_CLOSED)
        }
        val shownTerms = if (terms.size <= 6) terms.joinToString("+") { Printer.rational(it) }.replace("+-", "-")
        else terms.take(3).joinToString("+") { Printer.rational(it) }.replace("+-", "-") + "+\\cdots+" + Printer.rational(terms.last()).let { if (it.startsWith("-")) "($it)" else it }
        s.step("$whole=$shownTerms", "No closed-form rule covers this summand, so the $count terms are added directly (exact fractions)")
        val digits = maxOf(total.num.abs().toString().length, total.den.toString().length)
        if (digits > 24) {
            val bd = BigDecimal(total.num).divide(BigDecimal(total.den), java.math.MathContext(12, RoundingMode.HALF_UP))
            val txt = bd.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            s.note("The exact fraction has more than 24 digits, so a decimal is shown")
            return SolveResult.Solved(listOf(Answer("$whole\\approx$txt", "≈ $txt")), s.steps, s.notes)
        }
        return numericAnswer(whole, total, lead)
    }

    // ------------------------------------------------------------------ a sum equal to a number

    private fun equation(idx: Char, v: String, closed: Closed, lo: Aff, hi: Aff, rhs: Rational, fAt: (Long) -> Rational, bodyShown: String, formatted: Fmt): SolveResult {
        val d = hi.a - lo.a
        if (d.signum <= 0) throw UnsupportedMath("an equation whose upper bound does not grow with the unknown")
        // n must be a whole number with at least one term in the sum
        var nmin = 0L
        while (hi.eval(nmin) < lo.eval(nmin)) nmin++
        val rule = "$v must be a natural number with $v ≥ $nmin (so the sum has at least one term)"
        s.note("Only natural numbers $v ≥ $nmin are accepted")
        s.step("${formatted.tex}=${Printer.rational(rhs)}", "Replace the sum by its closed form and set it equal to ${plainOf(Printer.rational(rhs))}")
        val solutions = ArrayList<Long>()
        if (closed.exps.isEmpty()) {
            val p = closed.poly - UPoly.const(rhs)
            if (p.isZero) {
                s.step("0=0", "Always true")
                return SolveResult.Solved(listOf(Answer("$v\\ge $nmin\\ \\text{(every natural number)}", "$v ≥ $nmin (every natural number)")), s.steps, s.notes)
            }
            var l = BigInteger.ONE
            for (x in p.c) l = l.multiply(x.den).divide(l.gcd(x.den))
            val ip = p * Rational.of(l)
            val fp = SeriesFmt.poly(UPoly.of(ip.c.let { c -> if (ip.lead.signum < 0) c.map { -it } else c }), v)
            s.step("${fp.tex}=0", "Move everything to one side" + if (l != BigInteger.ONE) " and clear the fractions" else "")
            val f = Factoring.factor(p)
            val lin = f.factors.filter { it.first.degree == 1 }
            val rest = f.factors.filter { it.first.degree > 1 }
            val factoredFmt = SeriesFmt.factored(UPoly.of(ip.c.let { c -> if (ip.lead.signum < 0) c.map { -it } else c }), v)
            if (f.factors.size > 1 || f.factors.any { it.second > 1 }) s.step("${factoredFmt.tex}=0", "Factor")
            val roots = lin.map { (q, _) -> Rational.of(q.c[0].num.negate(), q.c[1].num) }.sorted()
            if (roots.isEmpty()) {
                s.step("\\text{no rational root}", "No rational solution, so no whole number works")
            } else {
                s.step(roots.joinToString("\\ \\text{or}\\ ") { "$v=${Printer.rational(it)}" }, "Solve")
            }
            if (rest.isNotEmpty()) s.note("The other factor has no rational root, so no whole number comes from it")
            for (r in roots) {
                val shown = plainOf(Printer.rational(r))
                when {
                    !r.isInteger -> s.step("$v=${Printer.rational(r)}", "Rejected: $v = $shown is not a whole number")
                    r.num.toLong() < nmin -> s.step("$v=${Printer.rational(r)}", "Rejected: $rule")
                    else -> solutions += r.num.toLong()
                }
            }
        } else {
            s.step("\\text{test }$v=$nmin,\\ ${nmin + 1},\\ \\ldots", "The closed form has a power of the unknown, so whole numbers $v ≥ $nmin are tried exactly (up to ${nmin + 400})")
            for (n in nmin..nmin + 400) if (closed.eval(n) == rhs) solutions += n
        }
        if (solutions.isEmpty()) {
            s.step("\\text{no natural number }$v\\ge $nmin", "No value of $v satisfies the equation")
            return SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)
        }
        s.step("$v\\in\\mathbb{N},\\ $v\\ge $nmin", "Accepted: $rule")
        for (n in solutions) {
            val a = lo.eval(n)
            val b = hi.eval(n)
            var sum = Rational.ZERO
            if (b - a > 50_000) throw UnsupportedMath("a check with that many terms")
            for (k in a..b) sum += fAt(k)
            if (sum != rhs) throw SumUnreliable("at $v = $n adding the terms gives ${plainOf(Printer.rational(sum))}, not ${plainOf(Printer.rational(rhs))}")
            val sumTex = "\\sum_{$idx=${lo.eval(n)}}^{${hi.eval(n)}}$bodyShown"
            s.step("$sumTex=${Printer.rational(sum)}", "Check $v = $n")
        }
        val tex = solutions.joinToString("\\ \\text{or}\\ ") { "$v=$it" }
        return SolveResult.Solved(listOf(Answer(tex, plainOf(tex))), s.steps, s.notes)
    }
}
