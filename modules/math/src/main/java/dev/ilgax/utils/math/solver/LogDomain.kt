package dev.ilgax.utils.math.solver

/** Domains of logarithm problems as lists of [Seg]: arguments > 0, bases > 0 and ≠ 1. */
internal object LogDomain {
    private val ZERO = RatFn(Poly.ZERO, Poly.ONE)

    fun intersect(a: List<Seg>, b: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (x in a) for (y in b) x.intersect(y)?.let { out += it }
        return out.sortedBy { it.lo?.d ?: Double.NEGATIVE_INFINITY }
    }

    /** {v : f(v) > 0}, silently. */
    fun positive(s: SolveSession, f: RatFn, v: Char): List<Seg> =
        s.quiet { Inequalities(s).setOf(f, ">", ZERO, v, Printer.ratFn(f), "0") }

    /** [set] without the points where the polynomial [p] is 0. */
    fun without(s: SolveSession, set: List<Seg>, p: Poly, v: Char): List<Seg> {
        var cur = set
        for (root in s.polyRoots(p, v) ?: emptyList()) cur = cur.flatMap { minusPoint(it, root) }
        return cur
    }

    private fun minusPoint(seg: Seg, p: Val): List<Seg> {
        val afterLo = seg.lo == null || (p.d > seg.lo.d && !p.sameAs(seg.lo))
        val beforeHi = seg.hi == null || (p.d < seg.hi.d && !p.sameAs(seg.hi))
        if (afterLo && beforeHi) return listOf(Seg(seg.lo, seg.loClosed, p, false), Seg(p, false, seg.hi, seg.hiClosed))
        if (seg.lo != null && p.sameAs(seg.lo) && seg.loClosed) return if (seg.isPoint) emptyList() else listOf(Seg(seg.lo, false, seg.hi, seg.hiClosed))
        if (seg.hi != null && p.sameAs(seg.hi) && seg.hiClosed) return listOf(Seg(seg.lo, seg.loClosed, seg.hi, false))
        return listOf(seg)
    }

    /** Joins touching pieces: (a, r) and {r} become (a, r]; (a, r) and (r, b) stay apart. */
    fun merge(pieces: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (p in pieces.sortedBy { it.lo?.d ?: Double.NEGATIVE_INFINITY }) {
            val last = out.lastOrNull()
            if (last != null && last.hi != null && p.lo != null && last.hi.sameAs(p.lo) && (last.hiClosed || p.loClosed)) {
                out[out.size - 1] = Seg(last.lo, last.loClosed, p.hi, p.hiClosed)
            } else out += p
        }
        return out
    }

    fun same(a: List<Seg>, b: List<Seg>): Boolean = a.size == b.size && a.indices.all { i ->
        val x = a[i]
        val y = b[i]
        (x.lo == null) == (y.lo == null) && (x.hi == null) == (y.hi == null) &&
            (x.lo == null || (x.lo.sameAs(y.lo!!) && x.loClosed == y.loClosed)) &&
            (x.hi == null || (x.hi.sameAs(y.hi!!) && x.hiClosed == y.hiClosed))
    }

    fun contains(set: List<Seg>, x: Double): Boolean = set.any { g ->
        (g.lo == null || x > g.lo.d || (g.loClosed && x == g.lo.d)) && (g.hi == null || x < g.hi.d || (g.hiClosed && x == g.hi.d))
    }

    fun tex(set: List<Seg>): String = set.joinToString("\\cup ") { it.tex() }.ifEmpty { "\\varnothing" }
}
