package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Inequalities with logarithms of the unknown. The domain is the intersection of "argument > 0" for every logarithm.
 * Logarithms of one family of bases are reduced monotonically to an algebraic inequality (the direction flips for a
 * base below 1) that the sign-chart code solves; anything else gets its boundary points from the equation solver and
 * a sign test inside the domain. The answer is intersected with the domain and sampled against the original
 * inequality before it is returned.
 */
internal class LogInequality(private val s: SolveSession, private val orig: ParsedLine) {
    private val zero = RatFn(Poly.ZERO, Poly.ONE)
    private fun one(p: Poly) = RatFn(p, Poly.ONE)
    private fun opTex(op: String) = when (op) { "<=" -> "\\le "; ">=" -> "\\ge "; else -> op }
    private fun flip(op: String) = when (op) { "<" -> ">"; ">" -> "<"; "<=" -> ">="; else -> "<=" }
    private fun strict(op: String) = op == "<" || op == ">"
    private fun plain(t: String) = plainOf(t)
    private fun noSolution() = SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)

    private fun logOf(b: Surd, arg: String) = if (b == Surd.E_SURD) "\\ln($arg)" else "\\log_{${Printer.surd(b)}}($arg)"
    private fun powTex(b: Surd, e: String): String {
        val t = Printer.surd(b)
        val base = if (b.isRational && b.rational.isInteger || b == Surd.E_SURD || b == Surd.PI_SURD) t else "($t)"
        return "$base^{$e}"
    }

    fun solve(line: ParsedLine): SolveResult {
        if (line.ops.size != 1) throw UnsupportedMath("a chain of inequalities with logarithms")
        val op = line.ops[0]
        if (op == "=") throw UnsupportedMath("an equation mixed into an inequality")
        if (line.parts.any { it.hasUnknownBase() }) throw UnsupportedMath("an inequality with a logarithm of an unknown base")
        val ev = Evaluator(s.atoms)
        val l = ev.eval(line.parts[0])
        val r = ev.eval(line.parts[1])
        if (s.atoms.list.any { it !is Atom.Log && it !is Atom.Pow }) throw UnsupportedMath("an inequality that mixes logarithms with roots of the unknown")
        var vars = (l.variables + r.variables).userVars
        for (a in s.atoms.list) vars = vars + when (a) { is Atom.Log -> a.arg.variables.userVars; is Atom.Pow -> a.exp.variables.userVars; else -> emptySet() }
        if (vars.size != 1) throw if (vars.isEmpty()) UnsupportedMath("an inequality without an unknown") else SolveFailure("it still has more than one unknown (${vars.joinToString(", ")})")
        val v = vars.first()

        // domain
        val logs = s.atoms.list.filterIsInstance<Atom.Log>().filter { !it.isConstant }
        var domain: List<Seg> = listOf(Seg.ALL)
        if (logs.isNotEmpty()) {
            val conds = logs.map { Printer.ratFn(it.arg.simplified().value) + ">0" }.distinct()
            s.step(conds.joinToString(",\\ "), "Domain: what is inside a logarithm must be > 0")
            for (lg in logs) domain = LogDomain.intersect(domain, LogDomain.positive(s, lg.arg, v))
            s.step(if (domain.isEmpty()) "\\varnothing" else "$v\\in ${LogDomain.tex(domain)}", if (domain.isEmpty()) "The domain is empty" else "Domain")
            if (domain.isEmpty()) return noSolution()
        }

        val reduced = reduce(l, r, op, v)
        val set: List<Seg>
        if (reduced != null) {
            set = LogDomain.intersect(reduced, domain)
            s.step(LogDomain.tex(set), "Intersect with the domain")
        } else set = general(line, l, r, op, v, domain, ev)
        check(set, op, v, domain)
        if (set.isEmpty()) {
            s.step("\\varnothing", "No x in the domain satisfies it")
            return noSolution()
        }
        if (logs.isNotEmpty() && LogDomain.same(set, domain)) s.step("$v\\in ${LogDomain.tex(set)}", "Every x in the domain works")
        return answer(v, set)
    }

    // ------------------------------------------------------------------ answers

    private fun answer(v: Char, set: List<Seg>): SolveResult {
        if (set.size == 1 && (set[0].lo == null) != (set[0].hi == null)) {
            val g = set[0]
            fun sep(t: String) = if (t.firstOrNull()?.isLetter() == true) " " else ""
            val tex = if (g.lo != null) g.lo.texOrDecimal().let { "$v${if (g.loClosed) "\\ge" else ">"}${sep(it)}$it" }
            else g.hi!!.texOrDecimal().let { "$v${if (g.hiClosed) "\\le" else "<"}${sep(it)}$it" }
            if (g.lo?.isApprox == true || g.hi?.isApprox == true) s.note("Some endpoints are approximate (no exact roots)")
            return SolveResult.Solved(listOf(Answer(tex, plain(tex))), s.steps, s.notes)
        }
        return Inequalities(s).answerOf(v, set)
    }

    // ------------------------------------------------------------------ reduction to an algebraic inequality

    private class Term(val atom: Atom.Log, val coef: Surd)

    /** The solution set of the algebraic inequality equivalent to [l] op [r] inside the domain; null when it doesn't reduce. */
    private fun reduce(l: RatFn, r: RatFn, op: String, v: Char): List<Seg>? {
        val diff = l - r
        if (!diff.den.isConstant) return null
        val p = diff.num.divBy(diff.den.constant)
        var gamma = Surd.ZERO
        val terms = ArrayList<Term>()
        for ((m, c) in p.terms) {
            if (m.isConstant) { gamma += c; continue }
            val only = m.powers.entries.singleOrNull()
            val atom = only?.takeIf { it.value == 1 }?.let { s.atoms[it.key] } as? Atom.Log ?: return null
            terms += Term(atom, c)
        }
        if (terms.isEmpty()) {
            val ok = when (op) { "<" -> gamma.signum() < 0; ">" -> gamma.signum() > 0; "<=" -> gamma.signum() <= 0; else -> gamma.signum() >= 0 }
            s.step("${Printer.surd(gamma)}${opTex(op)}0", if (ok) "The logarithms cancel: always true" else "The logarithms cancel: never true")
            return if (ok) listOf(Seg.ALL) else emptyList()
        }
        if (terms.size == 1) return single(terms[0], gamma, op, v)
        return several(terms, gamma, op, v)
    }

    private fun exactK(b: Surd, c: Surd): Surd? = if (c.isRational) NumberTheory.exactPow(b, c.rational) else null

    /** α·log_b A + γ op 0. */
    private fun single(t: Term, gamma: Surd, op: String, v: Char): List<Seg>? {
        val a = t.atom
        val c = -gamma / t.coef
        val k = exactK(a.base, c) ?: return null
        val op1 = if (t.coef.signum() < 0) flip(op) else op
        val argTex = Printer.ratFn(a.arg.simplified().value)
        val lg = logOf(a.base, argTex)
        s.step("$lg${opTex(op1)}${Printer.surd(c)}", if (t.coef.signum() < 0) "Isolate the logarithm (dividing by a negative number flips the sign)" else "Isolate the logarithm")
        val below = a.base.toDouble() < 1
        val op2 = if (below) flip(op1) else op1
        s.step("$argTex${opTex(op2)}${powTex(a.base, Printer.surd(c))}",
            if (below) "Base ${plain(Printer.surd(a.base))} is below 1: the logarithm is decreasing, so the inequality flips"
            else "Base ${plain(Printer.surd(a.base))} is above 1: the logarithm is increasing, the direction stays")
        return Inequalities(s).setOf(a.arg, op2, RatFn(Poly.const(k), Poly.ONE), v, argTex, Printer.surd(k))
    }

    private fun several(terms: List<Term>, gamma: Surd, op: String, v: Char): List<Seg>? {
        val common = NumberTheory.commonBase(terms.map { it.atom.base }) ?: return null
        if (terms.any { !it.coef.isRational }) return null
        val beta = common.base
        val alphas = terms.mapIndexed { i, t -> t.coef.rational / common.exps[i] }
        var lcm = BigInteger.ONE
        for (a in alphas) lcm = lcm.multiply(a.den).divide(lcm.gcd(a.den))
        val ms = alphas.map { (it * Rational.of(lcm)).num.toInt() }
        val c = -gamma * Surd.of(Rational.of(lcm))
        val k = exactK(beta, c) ?: return null
        val distinctBases = terms.map { it.atom.base }.distinct().size > 1
        val argsTex = terms.map { Printer.ratFn(it.atom.arg.simplified().value) }
        if (distinctBases) {
            s.step(terms.indices.joinToString(",\\ ") { i -> "${logOf(terms[i].atom.base, argsTex[i])}=${Printer.rational(Rational.ONE / common.exps[i])}${logOf(beta, argsTex[i])}" }, "Change of base to ${plain(Printer.surd(beta))}")
        }
        fun termTex(i: Int, withPower: Boolean): String {
            val m = Math.abs(ms[i])
            val simple = argsTex[i].all { it.isLetterOrDigit() }
            val inner = if (withPower && m != 1) (if (simple) argsTex[i] else "(${argsTex[i]})") + "^{$m}" else argsTex[i]
            val tx = logOf(beta, inner)
            return if (withPower || m == 1) tx else "$m$tx"
        }
        fun signed(i: Int, withPower: Boolean) = (if (ms[i] < 0) "-" else if (i == 0) "" else "+") + termTex(i, withPower)
        val rhs = "${opTex(op)}${Printer.surd(c)}"
        if (distinctBases || ms.any { it != 1 }) s.step(terms.indices.joinToString("") { signed(it, false) } + rhs, "Collect the logarithms")
        if (ms.any { Math.abs(it) != 1 }) s.step(terms.indices.joinToString("") { signed(it, true) } + rhs, "Power rule: n·log a = log aⁿ")
        var top = RatFn(Poly.ONE, Poly.ONE)
        var bot = RatFn(Poly.ONE, Poly.ONE)
        for ((i, t) in terms.withIndex()) if (ms[i] > 0) top = top * t.atom.arg.pow(ms[i]) else if (ms[i] < 0) bot = bot * t.atom.arg.pow(-ms[i])
        val topTex = Printer.ratFn(top.simplified().value)
        val botTex = Printer.ratFn(bot.simplified().value)
        val botIsOne = bot.num == Poly.ONE && bot.den == Poly.ONE
        val ratio = if (botIsOne) topTex else "\\frac{$topTex}{$botTex}"
        s.step(logOf(beta, ratio) + rhs, if (ms.any { it < 0 }) "Quotient rule: log a − log b = log (a/b)" else "Product rule: log a + log b = log (ab)")
        val below = beta.toDouble() < 1
        val op2 = if (below) flip(op) else op
        s.step("$ratio${opTex(op2)}${powTex(beta, Printer.surd(c))}",
            if (below) "Base ${plain(Printer.surd(beta))} is below 1: the logarithm is decreasing, so the inequality flips"
            else "Base ${plain(Printer.surd(beta))} is above 1: the logarithm is increasing, the direction stays")
        val rhsFn = bot * RatFn(Poly.const(k), Poly.ONE)
        if (!botIsOne) s.step("$topTex${opTex(op2)}${Printer.ratFn(rhsFn.simplified().value)}", "Multiply by the denominator (positive inside the domain)")
        return Inequalities(s).setOf(top, op2, rhsFn, v, topTex, Printer.ratFn(rhsFn))
    }

    // ------------------------------------------------------------------ boundary points and a sign test

    private fun valueAt(n: Node, v: Char, x: Double): Pair<Surd?, Double> {
        val exact = try { NodeExact.eval(n, mapOf(v to Surd.of(Rational.parse(BigDecimal(x).toPlainString())))) } catch (_: Exception) { null }
        return exact to (exact?.toDouble() ?: NodeNum.eval(n, mapOf(v to x)))
    }

    /** Sign of left − right at [x] (1, -1), 0 when too close to tell, null outside the domain. */
    private fun signAt(parts: List<Node>, v: Char, x: Double): Int? = try {
        val (ea, a) = valueAt(parts[0], v, x)
        val (eb, b) = valueAt(parts[1], v, x)
        if (ea != null && eb != null) (ea - eb).signum()
        else if (Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)))) 0
        else if (a > b) 1 else -1
    } catch (_: Undefined) { null }

    private fun holdsSign(sg: Int, op: String) = when (op) { "<" -> sg < 0; ">" -> sg > 0; "<=" -> sg <= 0; else -> sg >= 0 }

    private fun general(line: ParsedLine, l: RatFn, r: RatFn, op: String, v: Char, domain: List<Seg>, ev: Evaluator): List<Seg> {
        s.step("${Printer.ratFn(l)}=${Printer.ratFn(r)}", "Boundary points: solve the equation")
        val solver = AtomSolver(s, ev, line.parts[0], line.parts[1], Eq(l, r, Printer.ratFn(l), Printer.ratFn(r)))
        solver.emptyOk = true
        solver.solve()
        val roots = solver.found ?: throw UnsupportedMath("an inequality whose boundary points can't be found")
        val pieces = ArrayList<Seg>()
        val wantEqual = !strict(op)
        for (g in domain) {
            val cuts = roots.filter { z -> (g.lo == null || z.d > g.lo.d && !z.sameAs(g.lo)) && (g.hi == null || z.d < g.hi.d && !z.sameAs(g.hi)) }.sortedBy { it.d }
            val bounds = ArrayList<Val?>()
            bounds += g.lo
            bounds += cuts
            bounds += g.hi
            for (i in 0 until bounds.size - 1) {
                val lo = bounds[i]
                val hi = bounds[i + 1]
                var decided: Int? = null
                for (phi in listOf(0.5, 0.3, 0.7, 0.15, 0.85)) {
                    val x = when {
                        lo != null && hi != null -> lo.d + phi * (hi.d - lo.d)
                        lo != null -> lo.d + 1 + 2 * phi
                        hi != null -> hi.d - 1 - 2 * phi
                        else -> (phi - 0.5) * 4
                    }
                    val sg = signAt(line.parts, v, x) ?: continue
                    if (sg != 0) { decided = sg; break }
                }
                val sg = decided ?: throw SolveFailure("the two sides are too close to tell apart numerically between ${lo?.texOrDecimal() ?: "-∞"} and ${hi?.texOrDecimal() ?: "∞"}")
                if (holdsSign(sg, op)) pieces += Seg(lo, false, hi, false)
                if (i < cuts.size && wantEqual) pieces += Seg(cuts[i], true, cuts[i], true)
            }
        }
        val set = LogDomain.merge(pieces)
        if (roots.isNotEmpty()) s.step(LogDomain.tex(set), "Test a point between the boundary points inside the domain")
        else s.step(LogDomain.tex(set), "No boundary points: test one point inside the domain")
        if (set.any { it.lo?.isApprox == true || it.hi?.isApprox == true }) s.note("Some endpoints are approximate (no exact roots)")
        return set
    }

    // ------------------------------------------------------------------ safety net

    /** Samples the answer against the original inequality: between the breakpoints, beyond the ends, and at every endpoint. */
    private fun check(set: List<Seg>, op: String, v: Char, domain: List<Seg>) {
        val pts = java.util.TreeSet<Double>()
        for (g in set + domain) { g.lo?.let { pts += it.d }; g.hi?.let { pts += it.d } }
        val sorted = pts.toList()
        val tests = ArrayList<Double>()
        if (sorted.isEmpty()) tests += listOf(0.0, 1.0, -1.0, 7.5) else {
            tests += sorted.first() - 1.0
            tests += sorted.last() + 1.0
            for (i in 0 until sorted.size - 1) tests += listOf((sorted[i] + sorted[i + 1]) / 2, sorted[i] + (sorted[i + 1] - sorted[i]) / 5, sorted[i + 1] - (sorted[i + 1] - sorted[i]) / 5)
        }
        for (x in tests) {
            val sg = signAt(orig.parts, v, x)
            val inSet = LogDomain.contains(set, x)
            val expected = sg != null && sg != 0 && holdsSign(sg, op)
            if (sg == null && inSet || (sg != null && sg != 0 && expected != inSet)) {
                throw SolveFailure("the numeric check of the answer disagreed at $v ≈ ${Printer.num(x, 4)}, so no answer is shown")
            }
        }
    }
}
