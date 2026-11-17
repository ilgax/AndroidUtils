package dev.ilgax.utils.math.solver

/**
 * `log_B(A) = c` where the base B contains the unknown. It is rewritten as the power B^c = A (cleared of fractions),
 * solved with the ordinary machinery, and every candidate is checked against the original equation, which enforces
 * base > 0, base ≠ 1 and argument > 0.
 */
internal class UnknownBaseSolver(private val s: SolveSession, private val ev: Evaluator) {
    private fun noSolution() = SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)

    private fun pw(base: String, e: Int) = if (e == 1) base else "$base^{$e}"

    private fun wrap(t: String) = if (t.length > 1 && (t.contains('+') || t.drop(1).contains('-') || t.contains("\\frac"))) "($t)" else t

    private fun countLogVars(n: Node): Int = (if (n is Node.Func && n.hasLogVar()) 1 else 0) + n.children().sumOf { countLogVars(it) }

    /** The ordinary part of the check: an unknown inside the base, and nothing else with the unknown in a logarithm. */
    fun solve(line: ParsedLine): SolveResult {
        val msg = "a logarithm with an unknown base combined with other terms or other logarithms"
        if (line.parts.size != 2 || !line.isEquation) throw UnsupportedMath("a logarithm with an unknown base outside a single equation")
        if (line.parts.sumOf { countLogVars(it) } != 1) throw UnsupportedMath(msg)
        val fIndex = line.parts.indexOfFirst { it is Node.Func && it.base?.hasVariable() == true }
        if (fIndex < 0) throw UnsupportedMath(msg)
        val f = line.parts[fIndex] as Node.Func
        val other = line.parts[1 - fIndex]
        if (other.hasVariable()) throw UnsupportedMath(msg)
        val cv = ev.constantOf(ev.eval(other))
        val c = cv?.takeIf { it.isRational }?.rational ?: throw UnsupportedMath("a logarithm with an unknown base equal to something that isn't a rational number")
        val b = ev.eval(f.base!!)
        val a = ev.eval(f.arg)
        if ((b.variables + a.variables).atomSyms.isNotEmpty()) throw UnsupportedMath("a logarithm with an unknown base and roots, logarithms or powers of the unknown")
        val vars = (b.variables + a.variables).userVars
        if (vars.size != 1) throw UnsupportedMath("a logarithm with an unknown base and several unknowns")
        val v = vars.first()
        val bt = wrap(Printer.ratFn(b.simplified().value))
        val aRaw = Printer.ratFn(a.simplified().value)
        val bRaw = Printer.ratFn(b.simplified().value)
        val at = wrap(aRaw)
        val aConst = a.variables.isEmpty()
        val aValue = if (aConst) ev.constantOf(a) else null

        val conds = ArrayList<String>()
        conds += "$bRaw>0"
        conds += "$bRaw\\ne1"
        if (!aConst) conds += "$aRaw>0"
        s.step(conds.joinToString(",\\ "), "Domain: the base must be positive and not 1, the argument positive")
        if (aValue != null && aValue.signum() <= 0) {
            s.step("${Printer.surd(aValue)}>0", "The argument is not positive: no solution")
            return noSolution()
        }

        val one = RatFn(Poly.ONE, Poly.ONE)
        val left: RatFn
        val right: RatFn
        if (c.isZero) {
            s.step("$aRaw=1", "log_b a = 0 means a = 1 (for any base)")
            left = a
            right = one
        } else {
            s.step("$bt^{${Printer.rational(c)}}=$aRaw", "Rewrite the logarithm as a power: log_b a = c means bᶜ = a")
            val p = c.num.toInt()
            val q = c.den.toInt()
            if (Math.abs(p) > 12 || q > 12) throw UnsupportedMath("an exponent that is too large for the unknown base")
            if (q > 1) s.step("${pw(bt, p)}=${pw(at, q)}", "Raise both sides to the power $q to clear the fraction")
            if (p > 0) {
                left = b.pow(p)
                right = a.pow(q)
            } else {
                s.step("${pw(at, q)}\\cdot ${pw(bt, -p)}=1", "Move the power of the base to the other side")
                left = a.pow(q) * b.pow(-p)
                right = one
            }
        }
        val diff = (left - right).simplified().value
        val roots: Roots = if (diff.variables.isEmpty()) {
            if (diff.num.isZero) {
                s.step("${Printer.ratFn(left.simplified().value)}=${Printer.ratFn(right.simplified().value)}", "True for every x the logarithm is defined for")
                Roots.All
            } else {
                s.step("${Printer.ratFn(left.simplified().value)}\\ne${Printer.ratFn(right.simplified().value)}", "That is never true")
                Roots.None
            }
        } else s.solveOne(Eq(left, right, Printer.ratFn(left), Printer.ratFn(right)), v, check = false)
        return when (roots) {
            is Roots.None -> noSolution()
            is Roots.All -> {
                var set = LogDomain.positive(s, b, v)
                if (!aConst) set = LogDomain.intersect(set, LogDomain.positive(s, a, v))
                set = LogDomain.without(s, set, b.num - b.den, v)
                s.step(LogDomain.tex(set).let { "$v\\in $it" }, "Every x in the domain works")
                Inequalities(s).answerOf(v, set)
            }
            is Roots.Some -> {
                val kept = Candidates.verify(s, line.parts[0], line.parts[1], v, roots.values)
                if (kept.isEmpty()) return noSolution()
                if (kept.any { it.isApprox }) s.note("Decimal answers are approximate")
                SolveResult.Solved(listOf(s.rootsAnswer(v, kept.sortedBy { it.d })), s.steps, s.notes)
            }
        }
    }
}

/**
 * An equation whose unknown cancelled out of a change-of-base chain (`log_2 x · log_x 8 = 3`): both sides are numbers, so
 * the equation is an identity or a contradiction on the set where the original logarithms were defined.
 */
internal class LogIdentity(private val s: SolveSession) {
    fun solve(line: ParsedLine, restrictions: List<LogRewrites.Restriction>): SolveResult {
        val ev = Evaluator(s.atoms)
        val l = ev.eval(line.parts[0])
        val r = ev.eval(line.parts[1])
        val diff = ev.constantOf(l - r) ?: throw UnsupportedMath("a statement about logarithms that aren't exact")
        val shown = "${NodeTex.tex(line.parts[0])}=${NodeTex.tex(line.parts[1])}"
        if (!diff.isZero) {
            s.step(shown.replace("=", "\\ne"), "The two sides differ: never true")
            return SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)
        }
        val fns = restrictions.map { it.node to ev.eval(it.node) }
        val vars = fns.flatMap { it.second.variables.userVars }.toSortedSet()
        if (vars.size != 1) throw UnsupportedMath("a chain of logarithms with several unknowns")
        val v = vars.first()
        s.step(shown, "Both sides are equal wherever the logarithms are defined")
        val conds = ArrayList<String>()
        for ((n, f) in fns) {
            val t = Printer.ratFn(f.simplified().value)
            conds += "$t>0"
            if (restrictions.first { it.node == n }.notOne) conds += "$t\\ne1"
        }
        s.step(conds.distinct().joinToString(",\\ "), "Domain: bases and arguments of the original logarithms")
        var set: List<Seg> = listOf(Seg.ALL)
        for ((_, f) in fns) set = LogDomain.intersect(set, LogDomain.positive(s, f, v))
        for ((n, f) in fns) if (restrictions.first { it.node == n }.notOne) set = LogDomain.without(s, set, f.num - f.den, v)
        if (set.isEmpty()) return SolveResult.Solved(listOf(Answer("\\text{No solution}", "No solution")), s.steps, s.notes)
        return Inequalities(s).answerOf(v, set)
    }
}
