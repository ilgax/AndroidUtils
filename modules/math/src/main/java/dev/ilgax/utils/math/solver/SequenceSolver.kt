package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** Test hook: replaces a value the formulas gave, to prove that the numeric safety check catches a wrong answer. */
internal object SeqHooks {
    @Volatile var tamper: ((Rational) -> Rational)? = null
}

/** The terms generated one by one disagreed with a formula. */
internal class SeqUnreliable(val why: String) : Exception(why)

/**
 * Arithmetic and geometric sequences. Lines (separated by `\\` or commas) give an explicit formula (`a_n=3+4(n-1)`),
 * known terms (`a_3=7`), the difference `d` or the ratio `r` / `q`, and ask for a term (`a_{10}`), a sum (`S_{10}`,
 * `\sum_{k=1}^{10}a_k`) or the index of a value (`a_n=50`). The type is decided from what is given (d: arithmetic;
 * r or q: geometric; a formula linear in n: arithmetic; c·r^n: geometric); two terms alone fit both, and then both
 * readings are shown, unless a word (arithmetic, geometric, aritmetik, geometrik) says which one is meant.
 * Every answer is compared with terms generated one by one before it is shown.
 */
internal class SequenceSolver(private val s: SolveSession) {
    companion object {
        private val sub = Regex("""(?<![A-Za-z\\])[A-Za-z]_""")
        fun applies(latex: String) = sub.containsMatchIn(latex) && !DerivativeSolver.applies(latex)

        private val words = Regex("(arithmetic|aritmetik|geometric|geometrik|sequence|progression|dizisi|dizi)", RegexOption.IGNORE_CASE)
        private const val MAX_GEO = 5000
        private const val MAX_ARITH = 200_000
    }

    private sealed interface Q {
        class Term(val i: Int) : Q
        class Sum(val i: Int) : Q
        class Range(val from: Int, val to: Int) : Q
        data object TermFormula : Q
        data object SumFormula : Q
        class FindN(val v: Rational) : Q
    }

    private class Data(
        var hint: Boolean? = null,        // true: arithmetic, false: geometric
        var name: Char = 'a',
        var nvar: Char = 'n',
        val known: java.util.TreeMap<Int, Rational> = java.util.TreeMap(),
        var d: Rational? = null,
        var r: Rational? = null,
        var rName: Char = 'r',
        var explicit: Node? = null,
        var explicitForm: KForm? = null,
        val queries: ArrayList<Q> = ArrayList(),
    )

    /** One concrete sequence: the first term and the common difference or ratio. */
    private class Case(val arith: Boolean, val a1: Rational, val step: Rational, val tag: String, val steps: ArrayList<Step> = ArrayList(), val derived: Boolean)

    // ------------------------------------------------------------------ reading the lines

    private fun closing(t: String, open: Int): Int {
        var d = 0
        for (i in open until t.length) {
            if (t[i] == '{') d++
            if (t[i] == '}') { d--; if (d == 0) return i }
        }
        return -1
    }

    private fun splitItems(t: String): List<String> {
        val out = ArrayList<String>()
        for (line in t.split("\\\\")) {
            var d = 0
            val cur = StringBuilder()
            for (c in line) {
                if (c == '{' || c == '(' || c == '[') d++
                if (c == '}' || c == ')' || c == ']') d--
                if ((c == ',' || c == ';') && d == 0) { out += cur.toString(); cur.clear() } else cur.append(c)
            }
            out += cur.toString()
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseNode(src: String): Node {
        val lines = LatexParser.parse(src, null, AngleMode.Radians)
        if (lines.size != 1 || lines[0].parts.size != 1) throw UnsupportedMath("an equation or several expressions where one expression is expected")
        return lines[0].parts[0]
    }

    private fun number(src: String): Rational = try {
        RatEval.eval(parseNode(src), emptyMap())
    } catch (_: RatEval.NotRational) { throw UnsupportedMath("a value that isn't a rational number (“$src”)") }
    catch (u: Undefined) { throw SolveFailure(u.why) }

    private fun index(raw: String): String {
        val t = if (raw.startsWith("{") && raw.endsWith("}")) raw.substring(1, raw.length - 1) else raw
        if (t.isEmpty()) throw SolveFailure("a subscript is empty")
        return t
    }

    private fun intIndex(t: String): Int? {
        t.toIntOrNull()?.let { if (it < 1) throw SolveFailure("the index $it is below 1; terms are numbered from 1") else return it }
        if (t.contains("cdot") || t.contains("frac") || t.contains('/') || Regex("[0-9.]+").matches(t)) {
            throw SolveFailure("the index must be a natural number, not “${plainOf(t)}”")
        }
        return null
    }

    private val itemDef = Regex("""^([A-Za-z])_(\{[^{}]*\}|[A-Za-z]|\d+)(?:=(.*))?$""")
    private val sumCmd = Regex("""^\\sum_\{?([A-Za-z])=(-?\d+)\}?\^\{?(\w+)\}?\{?([A-Za-z])_\{?([A-Za-z])\}?\}?$""")

    private fun read(latex: String): Data {
        var t = compactTex(latex).replace("\\limits", "")
        val dt = Data()
        val low = t.lowercase()
        val ar = low.contains("arithmetic") || low.contains("aritmetik")
        val ge = low.contains("geometric") || low.contains("geometrik")
        if (ar && ge) throw SolveFailure("the text names both an arithmetic and a geometric sequence")
        if (ar) dt.hint = true
        if (ge) dt.hint = false
        t = words.replace(t, "")
        var nameSet = false
        fun setName(c: Char) { if (c != 'S' && !nameSet) { dt.name = c; nameSet = true } }
        for (item in splitItems(t)) {
            val sm = sumCmd.matchEntire(item)
            if (sm != null) {
                val lo = sm.groupValues[2].toInt()
                if (lo < 1) throw SolveFailure("a sum of terms must start at index 1 or later")
                val hiT = sm.groupValues[3]
                setName(sm.groupValues[4][0])
                val hi = hiT.toIntOrNull()
                dt.queries += if (hi != null) (if (lo == 1) Q.Sum(hi) else Q.Range(lo, hi)) else { if (lo != 1) throw UnsupportedMath("a symbolic sum of terms that doesn't start at 1"); dt.nvar = hiT[0]; Q.SumFormula }
                continue
            }
            val m = itemDef.matchEntire(item)
            if (m != null) {
                val letter = m.groupValues[1][0]
                val idx = index(m.groupValues[2])
                val rhs = m.groups[3]?.value
                val ask = rhs == null || rhs.isEmpty() || rhs == "?"
                if (letter == 'S') {
                    if (!ask) throw UnsupportedMath("a given sum S_n (only asking for a sum is supported)")
                    val i = intIndex(idx)
                    dt.queries += if (i != null) Q.Sum(i) else { if (idx.length != 1 || !idx[0].isLetter()) throw UnsupportedMath("a recurrence or an index like “$idx”"); dt.nvar = idx[0]; Q.SumFormula }
                    continue
                }
                setName(letter)
                val i = intIndex(idx)
                if (i == null && (idx.length != 1 || !idx[0].isLetter())) throw UnsupportedMath("a recurrence or an index like “$idx”")
                if (ask) {
                    dt.queries += if (i != null) Q.Term(i) else { dt.nvar = idx[0]; Q.TermFormula }
                    continue
                }
                if (i != null) {
                    val v = number(rhs!!)
                    val prev = dt.known[i]
                    if (prev != null && prev != v) throw SolveFailure("$letter$i is given twice with different values")
                    dt.known[i] = v
                    continue
                }
                val nv = idx[0]
                dt.nvar = nv
                val node = parseNode(rhs!!)
                if (nv in node.variableNames()) {
                    if (node.variableNames().any { it != nv }) throw UnsupportedMath("a formula with other letters than $nv (a recurrence or a parameter)")
                    if (dt.explicit != null) throw SolveFailure("two formulas for the general term are given")
                    dt.explicit = node
                    dt.explicitForm = try { KForm.of(node, nv) } catch (e: NotClosed) { throw UnsupportedMath("a general term that isn't linear in $nv or c·r^$nv (${e.why})") }
                } else dt.queries += Q.FindN(number(rhs))
                continue
            }
            val pm = Regex("""^([drq])=(.+)$""").matchEntire(item)
            if (pm != null) {
                val v = number(pm.groupValues[2])
                if (pm.groupValues[1] == "d") dt.d = v else { dt.r = v; dt.rName = pm.groupValues[1][0] }
                continue
            }
            throw SolveFailure("I can't read “${plainOf(item)}” as part of a sequence problem")
        }
        return dt
    }

    // ------------------------------------------------------------------ formatting

    private fun r(q: Rational) = Printer.rational(q)
    private fun par(q: Rational) = if (q.signum < 0) "(${r(q)})" else r(q)
    private fun parBase(q: Rational) = if (q.isInteger && q.signum >= 0) r(q) else "(${r(q)})"
    private fun plainR(q: Rational) = plainOf(r(q))
    private fun a(name: Char, idx: String) = "${name}_{$idx}"

    // ------------------------------------------------------------------ the readings

    private fun st(c: Case, latex: String, caption: String) { c.steps += Step(latex, caption) }

    private fun entryOf(d: Data): Pair<Int, Rational>? = d.known.entries.firstOrNull()?.let { it.key to it.value }

    private fun arithmetic(d: Data, tag: String): Case {
        val nm = d.name
        val x = d.explicitForm
        if (x != null) {
            val (dd, a0) = x.affine() ?: throw SolveFailure("the formula isn't linear in ${d.nvar}")
            val a1 = dd + a0
            val c = Case(true, a1, dd, tag, derived = false)
            st(c, "${nm}_{n}=${nm}_{1}+(n-1)d", "Arithmetic sequence: a_n = a_1 + (n−1)d")
            st(c, "${nm}_{n}=${NodeTex.tex(d.explicit!!)}", "The given formula")
            st(c, "d=${r(dd)},\\ ${nm}_{1}=${r(a1)}", "d is the coefficient of ${d.nvar}; a_1 is the value at ${d.nvar} = 1")
            return c
        }
        val dv = d.d
        if (dv != null) {
            val (i, v) = entryOf(d) ?: throw SolveFailure("the first term (or any term) is needed besides d")
            val a1 = v - Rational.of(i - 1L) * dv
            val c = Case(true, a1, dv, tag, derived = i != 1)
            st(c, "${nm}_{n}=${nm}_{1}+(n-1)d", "Arithmetic sequence: a_n = a_1 + (n−1)d")
            if (i != 1) {
                st(c, "${r(v)}=${nm}_{1}+(${i}-1)\\cdot ${par(dv)}", "Put in the known term a_$i and d")
                st(c, "${nm}_{1}=${r(a1)}", "Solve for a_1")
            }
            return c
        }
        if (d.known.size < 2) throw SolveFailure("two terms (or one term and d) are needed to find an arithmetic sequence")
        val (e1, e2) = d.known.entries.toList().take(2)
        val i = e1.key; val j = e2.key
        val dd = (e2.value - e1.value) / Rational.of((j - i).toLong())
        val a1 = e1.value - Rational.of(i - 1L) * dd
        val c = Case(true, a1, dd, tag, derived = true)
        st(c, "${nm}_{n}=${nm}_{1}+(n-1)d", "Arithmetic sequence: a_n = a_1 + (n−1)d")
        st(c, "${nm}_{$j}-${nm}_{$i}=(${j}-${i})d", "Subtract the two known terms: a_j − a_i = (j − i)d")
        st(c, "${r(e2.value)}-${par(e1.value)}=${j - i}d", "Put in the values")
        st(c, "d=${r(dd)}", "Solve for d")
        st(c, "${r(e1.value)}=${nm}_{1}+(${i}-1)\\cdot ${par(dd)}", "Put d and a_$i into a_n = a_1 + (n−1)d")
        st(c, "${nm}_{1}=${r(a1)}", "Solve for a_1")
        return c
    }

    private fun geoCases(d: Data, tag: String): List<Case> {
        val nm = d.name
        val rn = d.rName
        val x = d.explicitForm
        if (x != null) {
            if (x.t.size != 1) throw UnsupportedMath("a general term that mixes a power and other terms")
            val (k, c0) = x.t.entries.first()
            if (k.p != 0 || k.r == Rational.ONE) throw SolveFailure("the formula isn't of the form c·r^${d.nvar}")
            val a1 = c0 * k.r
            val c = Case(false, a1, k.r, tag, derived = false)
            st(c, "${nm}_{n}=${nm}_{1}\\cdot r^{n-1}", "Geometric sequence: a_n = a_1·r^(n−1)")
            st(c, "${nm}_{n}=${NodeTex.tex(d.explicit!!)}", "The given formula")
            st(c, "r=${r(k.r)},\\ ${nm}_{1}=${r(a1)}", "r is the base of the power; a_1 is the value at ${d.nvar} = 1")
            return listOf(c)
        }
        val rv = d.r
        if (rv != null) {
            if (rv.isZero) throw SolveFailure("the ratio $rn = 0 isn't allowed (every term after the first would be 0)")
            val (i, v) = entryOf(d) ?: throw SolveFailure("the first term (or any term) is needed besides $rn")
            val a1 = v / rv.pow(i - 1)
            val c = Case(false, a1, rv, tag, derived = i != 1)
            st(c, "${nm}_{n}=${nm}_{1}\\cdot r^{n-1}", "Geometric sequence: a_n = a_1·r^(n−1)")
            if (i != 1) {
                st(c, "${r(v)}=${nm}_{1}\\cdot ${parBase(rv)}^{${i}-1}", "Put in the known term a_$i and r")
                st(c, "${nm}_{1}=${r(a1)}", "Solve for a_1")
            }
            return listOf(c)
        }
        if (d.known.size < 2) throw SolveFailure("two terms (or one term and r) are needed to find a geometric sequence")
        val (e1, e2) = d.known.entries.toList().take(2)
        val i = e1.key; val j = e2.key; val g = j - i
        if (e1.value.isZero) throw SolveFailure("a geometric sequence with a zero term is all zeros, so the ratio can't be found")
        val ratio = e2.value / e1.value
        val shared = ArrayList<Step>()
        shared += Step("${nm}_{n}=${nm}_{1}\\cdot r^{n-1}", "Geometric sequence: a_n = a_1·r^(n−1)")
        shared += Step("\\frac{${nm}_{$j}}{${nm}_{$i}}=r^{${j}-${i}}", "Divide the two known terms: a_j / a_i = r^(j − i)")
        shared += Step("\\frac{${r(e2.value)}}{${r(e1.value)}}=r^{$g}", "Put in the values")
        shared += Step("${r(ratio)}=r^{$g}", "Simplify")
        val roots: List<Rational> = when {
            ratio.signum == 0 -> emptyList()
            ratio.signum < 0 -> if (g % 2 == 1) NumberTheory.exactRoot(ratio.abs(), g)?.let { listOf(-it) } ?: emptyList() else emptyList()
            else -> NumberTheory.exactRoot(ratio, g)?.let { if (g % 2 == 0) listOf(it, -it) else listOf(it) } ?: emptyList()
        }
        if (roots.isEmpty()) {
            val why = if (ratio.signum <= 0 && g % 2 == 0) "an even power can't be negative" else "the $g${ordinal(g)} root of ${plainR(ratio)} isn't a rational number"
            throw SolveFailure("no geometric sequence with a rational ratio fits these terms ($why)")
        }
        val shown = if (roots.size == 2) "r=\\pm ${r(roots[0])}" else "r=${r(roots[0])}"
        shared += Step(shown, if (roots.size == 2) "Take the root of even order: both signs are possible" else "Take the root")
        return roots.map { rv ->
            val a1 = e1.value / rv.pow(i - 1)
            val c = Case(false, a1, rv, if (roots.size == 2) "$tag, r = ${plainR(rv)}" else tag, derived = true)
            if (roots.size == 2) st(c, "r=${r(rv)}", "Case r = ${plainR(rv)}")
            st(c, "${r(e1.value)}=${nm}_{1}\\cdot ${parBase(rv)}^{${i}-1}", "Put r and a_$i into a_n = a_1·r^(n−1)")
            st(c, "${nm}_{1}=${r(a1)}", "Solve for a_1")
            // the shared lines come first once
            if (rv === roots[0]) c.steps.addAll(0, shared)
            c
        }
    }

    private fun ordinal(g: Int) = when { g % 100 in 11..13 -> "th"; g % 10 == 1 -> "st"; g % 10 == 2 -> "nd"; g % 10 == 3 -> "rd"; else -> "th" }

    // ------------------------------------------------------------------ general term, sums, values

    private fun generalTerm(c: Case, nm: Char): Fmt {
        if (c.arith) return SeriesFmt.poly(UPoly.of(listOf(c.a1 - c.step, c.step)), "n")
        if (c.step == Rational.ONE) return Fmt.rat(c.a1)
        val base = if (c.step.isInteger && c.step.signum >= 0) Fmt.of(c.step.num.toString()) else Fmt("(${r(c.step)})", if (c.step.isInteger) "(${c.step.num})" else "(${c.step.num}/${c.step.den})")
        val pw = Fmt("${base.tex}^{n-1}", "${base.plain}^(n-1)")
        return when (c.a1) {
            Rational.ONE -> pw
            -Rational.ONE -> Fmt("-" + pw.tex, "-" + pw.plain)
            else -> Fmt("${r(c.a1)}\\cdot ${pw.tex}", "${plainR(c.a1)}·${pw.plain}")
        }
    }

    private fun termAt(c: Case, i: Long): Rational =
        if (c.arith) c.a1 + Rational.of(i - 1) * c.step else c.a1 * powOf(c.step, i - 1)

    private fun powOf(b: Rational, e: Long): Rational {
        if (Math.abs(e) > MAX_GEO) throw UnsupportedMath("a geometric term beyond index $MAX_GEO")
        return b.pow(e.toInt())
    }

    private fun sumTo(c: Case, n: Long): Rational =
        if (c.arith) Rational.of(n) / Rational.of(2) * (Rational.of(2) * c.a1 + Rational.of(n - 1) * c.step)
        else if (c.step == Rational.ONE) Rational.of(n) * c.a1
        else c.a1 * (powOf(c.step, n) - Rational.ONE) / (c.step - Rational.ONE)

    private fun cap(c: Case, i: Long) {
        if (!c.arith && i > MAX_GEO && c.step.abs() != Rational.ONE) throw UnsupportedMath("a geometric sequence beyond index $MAX_GEO")
        if (i > MAX_ARITH) throw UnsupportedMath("an index above $MAX_ARITH")
    }

    private fun hook(v: Rational) = SeqHooks.tamper?.invoke(v) ?: v

    private fun generate(c: Case, count: Int): List<Rational> {
        val out = ArrayList<Rational>(count)
        var t = c.a1
        for (k in 1..count) { out += t; t = if (c.arith) t + c.step else t * c.step }
        return out
    }

    private fun bad(what: String, got: Rational, want: Rational): Nothing =
        throw SeqUnreliable("$what: the formula gives ${plainR(got)}, generating the terms gives ${plainR(want)}")

    // ------------------------------------------------------------------ queries

    private class Fact(val tex: String, val plain: String)

    private fun answerTerm(c: Case, nm: Char, i: Int): Fact {
        cap(c, i.toLong())
        val v = hook(termAt(c, i.toLong()))
        val direct = generate(c, i).last()
        if (v != direct) bad("a_$i", v, direct)
        if (c.arith) st(c, "${a(nm, "$i")}=${a(nm, "1")}+(${i}-1)d=${r(c.a1)}+(${i}-1)\\cdot ${par(c.step)}=${r(v)}", "Term ${nm}_$i from a_n = a_1 + (n−1)d")
        else st(c, "${a(nm, "$i")}=${a(nm, "1")}r^{${i}-1}=${r(c.a1)}\\cdot ${parBase(c.step)}^{${i}-1}=${r(v)}", "Term ${nm}_$i from a_n = a_1·r^(n−1)")
        return Fact("${a(nm, "$i")}=${r(v)}", "${nm}_$i = ${plainR(v)}")
    }

    private fun sumSteps(c: Case, n: Int, v: Rational) {
        if (c.arith) st(c, "S_{$n}=\\frac{$n}{2}(2a_{1}+($n-1)d)=\\frac{$n}{2}(2\\cdot ${par(c.a1)}+($n-1)\\cdot ${par(c.step)})=${r(v)}", "Sum of the first n terms: S_n = n/2 (2a_1 + (n−1)d)")
        else if (c.step == Rational.ONE) st(c, "S_{$n}=$n\\cdot a_{1}=$n\\cdot ${par(c.a1)}=${r(v)}", "r = 1: every term equals a_1, so S_n = n·a_1")
        else st(c, "S_{$n}=a_{1}\\cdot\\frac{r^{$n}-1}{r-1}=${par(c.a1)}\\cdot\\frac{${parBase(c.step)}^{$n}-1}{${par(c.step)}-1}=${r(v)}", "Sum of the first n terms: S_n = a_1 (r^n − 1)/(r − 1)")
    }

    private fun answerSum(c: Case, n: Int): Fact {
        cap(c, n.toLong())
        val v = hook(sumTo(c, n.toLong()))
        val direct = generate(c, n).fold(Rational.ZERO) { a, b -> a + b }
        if (v != direct) bad("S_$n", v, direct)
        sumSteps(c, n, v)
        return Fact("S_{$n}=${r(v)}", "S_$n = ${plainR(v)}")
    }

    private fun answerRange(c: Case, p: Int, q: Int): Fact {
        cap(c, q.toLong())
        if (q < p) {
            s.note("Empty sum: the last index is below the first, so the sum is 0")
            st(c, "\\sum_{k=$p}^{$q}a_k=0", "No terms")
            return Fact("\\sum_{k=$p}^{$q}a_{k}=0", "Σ a_k (k=$p..$q) = 0")
        }
        val v = hook(sumTo(c, q.toLong()) - sumTo(c, p - 1L))
        val terms = generate(c, q)
        val direct = terms.drop(p - 1).fold(Rational.ZERO) { a, b -> a + b }
        if (v != direct) bad("the sum of a_$p … a_$q", v, direct)
        st(c, "\\sum_{k=$p}^{$q}a_{k}=S_{$q}-S_{${p - 1}}=${r(sumTo(c, q.toLong()))}-${par(sumTo(c, p - 1L))}=${r(v)}", "The terms from $p to $q: S_$q − S_${p - 1}")
        return Fact("\\sum_{k=$p}^{$q}a_{k}=${r(v)}", "Σ a_k (k=$p..$q) = ${plainR(v)}")
    }

    private fun answerFormula(c: Case, nm: Char): Fact {
        val f = generalTerm(c, nm)
        val terms = generate(c, 12)
        for (k in 1..12) {
            val got = hook(termAt(c, k.toLong()))
            if (got != terms[k - 1]) bad("a_$k", got, terms[k - 1])
        }
        st(c, "${nm}_{n}=${f.tex}", "The general term")
        return Fact("${nm}_{n}=${f.tex}", "${nm}_n = ${f.plain}")
    }

    private fun answerSumFormula(c: Case): Fact {
        val f: Fmt = if (c.arith) {
            // n/2 (2a1 + (n-1)d) as a polynomial in n
            val p = UPoly.of(listOf(Rational.ZERO, c.a1 - c.step / Rational.of(2), c.step / Rational.of(2)))
            SeriesFmt.factored(p, "n")
        } else if (c.step == Rational.ONE) SeriesFmt.factored(UPoly.of(listOf(Rational.ZERO, c.a1)), "n")
        else {
            val k = c.a1 / (c.step - Rational.ONE)
            SeriesFmt.closed(Closed(UPoly.const(-k), mapOf(c.step to k)), "n")
        }
        // the closed form against the terms
        val terms = generate(c, 12)
        var run = Rational.ZERO
        for (n in 1..12) {
            run += terms[n - 1]
            val got = hook(sumTo(c, n.toLong()))
            if (got != run) bad("S_$n", got, run)
        }
        if (c.arith) st(c, "S_{n}=\\frac{n}{2}(2a_{1}+(n-1)d)=\\frac{n}{2}(2\\cdot ${par(c.a1)}+(n-1)\\cdot ${par(c.step)})", "Sum of the first n terms: S_n = n/2 (2a_1 + (n−1)d)")
        else if (c.step == Rational.ONE) st(c, "S_{n}=n\\cdot a_{1}", "r = 1: every term equals a_1, so S_n = n·a_1")
        else st(c, "S_{n}=a_{1}\\cdot\\frac{r^{n}-1}{r-1}=${par(c.a1)}\\cdot\\frac{${parBase(c.step)}^{n}-1}{${par(c.step)}-1}", "Sum of the first n terms: S_n = a_1 (r^n − 1)/(r − 1)")
        st(c, "S_{n}=${f.tex}", "Simplify")
        return Fact("S_{n}=${f.tex}", "S_n = ${f.plain}")
    }

    private fun answerFindN(c: Case, nm: Char, v: Rational): Fact {
        val none = Fact("\\text{No natural number }n\\text{ gives }${nm}_{n}=${r(v)}", "No natural number n gives ${nm}_n = ${plainR(v)}")
        val scan = 3000
        val terms = generate(c, minOf(scan, if (c.arith) scan else 1500))
        fun confirm(n: Long?) {
            val first = terms.indexOfFirst { it == v } + 1
            if (n == null) {
                if (first > 0) throw SeqUnreliable("no natural n was found, but a_$first = ${plainR(v)}")
            } else if (n <= terms.size) {
                val claimed = hook(Rational.of(n))
                if (claimed != Rational.of(first.toLong())) throw SeqUnreliable("the formula says a_$n = ${plainR(v)}, generating the terms gives ${if (first > 0) "the first match at a_$first" else "no match"}")
            }
        }
        if (c.arith) {
            val d = c.step
            st(c, "${r(v)}=${a(nm, "1")}+(n-1)d=${r(c.a1)}+(n-1)\\cdot ${par(d)}", "Put a_n = ${plainR(v)} into a_n = a_1 + (n−1)d")
            if (d.isZero) {
                if (v == c.a1) { st(c, "${nm}_{n}=${r(v)}", "d = 0: every term equals ${plainR(v)}"); return Fact("n\\text{ can be any natural number}", "n can be any natural number") }
                st(c, "${r(v)}\\ne ${r(c.a1)}", "d = 0: every term equals ${plainR(c.a1)}, never ${plainR(v)}")
                return none
            }
            val m = (v - c.a1) / d
            st(c, "n-1=\\frac{${r(v)}-${par(c.a1)}}{${par(d)}}=${r(m)}", "Solve for n − 1")
            val n = m + Rational.ONE
            if (!n.isInteger || n.signum <= 0) {
                st(c, "n=${r(n)}", "n = ${plainR(n)} is not a natural number, so there is no such term")
                confirm(null)
                return none
            }
            st(c, "n=${r(n)}", "n is a natural number")
            confirm(n.num.toLong())
            return Fact("n=${r(n)}", "n = ${plainR(n)}")
        }
        val rr = c.step
        st(c, "${r(v)}=${a(nm, "1")}r^{n-1}=${r(c.a1)}\\cdot ${parBase(rr)}^{n-1}", "Put a_n = ${plainR(v)} into a_n = a_1·r^(n−1)")
        if (c.a1.isZero) {
            if (v.isZero) return Fact("n\\text{ can be any natural number}", "n can be any natural number")
            return none
        }
        val t = v / c.a1
        st(c, "${parBase(rr)}^{n-1}=${r(t)}", "Divide both sides by a_1")
        if (rr == Rational.ONE) {
            if (t == Rational.ONE) return Fact("n\\text{ can be any natural number}", "n can be any natural number")
            st(c, "1^{n-1}=1\\ne ${r(t)}", "r = 1: every term equals a_1")
            return none
        }
        // exact search for the exponent
        var found: Long? = null
        var m = 0L
        var p = Rational.ONE
        val ar = rr.abs()
        while (m <= 1500) {
            if (p == t) { found = m; break }
            if (ar > Rational.ONE && p.abs() > t.abs()) break
            if (ar < Rational.ONE && p.abs() < t.abs()) break
            p *= rr; m++
        }
        if (found != null) {
            if (rr.signum > 0) st(c, "n-1=\\log_{${r(rr)}}${par(t)}=$found", "Take the logarithm to base r (exact)")
            else st(c, "n-1=$found", "Powers of ${plainR(rr)}: ${parBase(rr).let { plainOf(it) }}^$found = ${plainR(t)}")
            st(c, "n=${found + 1}", "n is a natural number")
            confirm(found + 1)
            return Fact("n=${found + 1}", "n = ${found + 1}")
        }
        if (rr.signum > 0 && t.signum > 0) {
            val approx = 1 + Math.log(t.toDouble()) / Math.log(rr.toDouble())
            st(c, "n=1+\\log_{${r(rr)}}${par(t)}\\approx ${Printer.num(approx, 3)}", "The exponent isn't a whole number, so n is not a natural number")
        } else st(c, "${parBase(rr)}^{n-1}\\ne ${r(t)}", "No power of r equals ${plainR(t)}")
        confirm(null)
        return none
    }

    // ------------------------------------------------------------------ entry

    fun solve(latex: String): SolveResult = try { run(latex) } catch (u: Undefined) { SolveResult.Failed(u.why) }

    private fun run(latex: String): SolveResult {
        val d = read(latex)
        val nm = d.name
        if (d.d != null && d.r != null) throw SolveFailure("both d (arithmetic) and r (geometric) are given")
        if (d.hint == true && d.r != null) throw SolveFailure("the text says arithmetic, but a ratio is given")
        if (d.hint == false && d.d != null) throw SolveFailure("the text says geometric, but a difference d is given")
        val x = d.explicitForm
        val xArith = x != null && x.affine() != null
        val xGeo = x != null && !xArith
        if (d.hint == true && xGeo) throw SolveFailure("the text says arithmetic, but the formula is a power of n")
        if (d.hint == false && xArith) throw SolveFailure("the text says geometric, but the formula is linear in n")
        if (d.queries.isEmpty() && d.known.isEmpty() && x == null) throw SolveFailure("there is no sequence to work with")

        // which readings fit
        val wantArith = d.hint == true || (d.hint == null && d.r == null && !xGeo)
        val wantGeo = d.hint == false || (d.hint == null && d.d == null && !xArith)
        val groups = ArrayList<Pair<String, List<Case>>>()
        var skipped: String? = null
        if (wantArith) groups += "Arithmetic" to listOf(arithmetic(d, "Arithmetic"))
        if (wantGeo) {
            try { groups += "Geometric" to geoCases(d, "Geometric") } catch (f: SolveFailure) {
                if (groups.isEmpty()) throw f
                skipped = f.message
            }
        }
        val multi = groups.size > 1
        if (multi) s.note("Two terms alone fit both an arithmetic and a geometric sequence, so both readings are shown (write “arithmetic” or “geometric” to choose)")
        if (skipped != null) s.note("No geometric reading: $skipped")
        val cases = groups.flatMap { it.second }
        val showCases = cases.size > 1

        val queries = if (d.queries.isEmpty()) listOf(Q.TermFormula) else d.queries.toList()
        val onlyFormula = d.queries.isEmpty()
        val lines = ArrayList<Answer>()
        for (c in cases) {
            val facts = ArrayList<Fact>()
            val derivedFacts = c.derived || d.queries.isEmpty()
            if (derivedFacts) {
                if (c.arith) {
                    facts += Fact("d=${r(c.step)}", "d = ${plainR(c.step)}")
                    facts += Fact("${nm}_{1}=${r(c.a1)}", "${nm}_1 = ${plainR(c.a1)}")
                } else {
                    facts += Fact("r=${r(c.step)}", "r = ${plainR(c.step)}")
                    facts += Fact("${nm}_{1}=${r(c.a1)}", "${nm}_1 = ${plainR(c.a1)}")
                }
            }
            var general = false
            for (q in queries) {
                when (q) {
                    is Q.Term -> facts += answerTerm(c, nm, q.i)
                    is Q.Sum -> facts += answerSum(c, q.i)
                    is Q.Range -> facts += answerRange(c, q.from, q.to)
                    Q.TermFormula -> { facts += answerFormula(c, nm); general = true }
                    Q.SumFormula -> facts += answerSumFormula(c)
                    is Q.FindN -> facts += answerFindN(c, nm, q.v)
                }
            }
            if (c.derived && !general && !onlyFormula) facts.add(2.coerceAtMost(facts.size), answerFormula(c, nm))
            // verify the data the reading came from
            verifyGiven(c, d)
            val tag = c.tag
            if (showCases) {
                lines += Answer("\\text{$tag: }" + facts.joinToString(",\\ ") { it.tex }, "$tag: " + facts.joinToString(", ") { it.plain })
            } else {
                for (f in facts) lines += Answer(f.tex, f.plain)
            }
        }
        for (c in cases) for (st in c.steps) s.step(st.latex, if (showCases && !st.caption.startsWith(c.tag.substringBefore(','))) "${c.tag}: ${st.caption}" else st.caption)
        return SolveResult.Solved(lines, s.steps, s.notes)
    }

    private fun verifyGiven(c: Case, d: Data) {
        val terms = generate(c, 12)
        for ((i, v) in d.known) {
            if (i <= MAX_GEO && (c.arith || i <= 1500)) {
                val got = hook(termAt(c, i.toLong()))
                if (got != v) throw SolveFailure("the given terms don't all fit one ${if (c.arith) "arithmetic" else "geometric"} sequence (a_$i would be ${plainR(got)}, not ${plainR(v)})")
            }
        }
        val node = d.explicit
        if (node != null) for (k in 1..12) {
            val want = RatEval.eval(node, mapOf(d.nvar to Rational.of(k.toLong())))
            if (want != hook(terms[k - 1])) throw SeqUnreliable("a_$k: the given formula gives ${plainR(want)}, the sequence found gives ${plainR(terms[k - 1])}")
        }
    }
}
