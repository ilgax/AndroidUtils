package dev.ilgax.utils.math.solver

import java.math.BigDecimal
import java.math.MathContext

/** Where the variable goes: a finite exact point, or ±∞ ([inf] = ±1). */
internal class Pt(val exact: Surd?, val inf: Int) {
    val isInf get() = inf != 0
    val d: Double get() = exact?.toDouble() ?: Double.NaN
}

/** Extended-real value of an expression at the limit point ("direct substitution with infinities"). */
internal sealed interface EV
internal class EF(val exact: Surd?, val d: Double) : EV {
    val isZero get() = if (exact != null) exact.isZero else Math.abs(d) < 1e-11
    val sign get() = if (isZero) 0 else if (d > 0) 1 else -1
    companion object {
        fun of(s: Surd) = EF(s, s.toDouble())
        val ZERO = of(Surd.ZERO)
    }
}
/** ±∞; [sign] 0 means "infinite, sign differs between the two sides". */
internal class EInf(val sign: Int) : EV
/** Bounded, no limit (sin or cos of something that runs off to infinity). */
internal data object EBounded : EV

/** The substitution gave an indeterminate form such as `0/0`; [form] names it. */
internal class Indet(val form: String) : Exception(form, null, false, false)
/** The answer depends on the side of the point. */
internal class NeedSides : Exception(null, null, false, false)
/** Leading terms cancelled (∞−∞ at infinity). */
internal class Cancel : Exception(null, null, false, false)
internal class NotAlgebraic : Exception(null, null, false, false)

/** A [Surd] as a [Node] when it is a rational plus a rational multiple of π, else null. */
internal fun surdNode(s: Surd): Node? {
    if (s.isRational) return Node.Number(s.rational)
    val parts = ArrayList<Node>()
    for ((k, q) in s.terms) {
        when {
            k == SKey.ONE -> parts += Node.Number(q)
            k == SKey(java.math.BigInteger.ONE, 2, 0) -> parts += Node.Product(listOf(Node.Number(q), Node.Const('π')))
            else -> return null
        }
    }
    return if (parts.size == 1) parts[0] else Node.Sum(parts)
}

internal class Ext(private val v: Char, private val pt: Pt, private val side: Int) {
    var squeezed = false

    private val envS: Map<Char, Surd> = if (!pt.isInf && pt.exact != null) mapOf(v to pt.exact) else emptyMap()

    /** Evaluates; an infinity whose sign differs between the sides is [NeedSides]. */
    fun top(n: Node): EV {
        val r = eval(n)
        if (r is EInf && r.sign == 0) throw NeedSides()
        return r
    }

    private fun tryExact(n: Node): EV? {
        if (pt.isInf && v in n.variableNames()) return null
        if (n.any { it is Node.Power && it.exp.variableNames().isNotEmpty() }) return null
        try {
            return EF.of(NodeExact.eval(n, envS))
        } catch (_: NotExact) {
            try {
                val x = NodeNum.eval(n, if (pt.isInf) emptyMap() else mapOf(v to pt.d))
                if (!x.isNaN() && !x.isInfinite()) return EF(null, x)
            } catch (_: Undefined) {
            } catch (_: ArithmeticException) {
            }
        } catch (_: Undefined) {
        } catch (_: UnsupportedMath) {
        } catch (_: ArithmeticException) {
        }
        return null
    }

    fun eval(n: Node): EV {
        tryExact(n)?.let { return it }
        return when (n) {
            is Node.Number -> EF.of(Surd.of(n.value))
            is Node.Const -> EF.of(if (n.symbol == 'π') Surd.PI_SURD else Surd.E_SURD)
            is Node.Variable -> when {
                n.name != v -> throw UnsupportedMath("a second unknown")
                pt.isInf -> EInf(pt.inf)
                else -> EF.of(pt.exact!!)
            }
            is Node.Neg -> neg(eval(n.arg))
            is Node.Sum -> n.terms.map { eval(it) }.reduce { a, b -> add(a, b) }
            is Node.Product -> n.factors.map { eval(it) }.reduce { a, b -> mul(a, b) }
            is Node.Quotient -> div(n)
            is Node.Power -> power(n)
            is Node.Root -> root(n)
            is Node.Abs -> when (val a = eval(n.arg)) {
                is EF -> if (a.exact != null) EF.of(if (a.exact.signum() < 0) -a.exact else a.exact) else EF(null, Math.abs(a.d))
                is EInf -> EInf(1)
                EBounded -> EBounded
            }
            is Node.Func -> func(n)
            is Node.Trig -> trig(n)
        }
    }

    // ------------------------------------------------------------------ arithmetic

    private fun neg(a: EV): EV = when (a) {
        is EF -> if (a.exact != null) EF.of(-a.exact) else EF(null, -a.d)
        is EInf -> EInf(-a.sign)
        EBounded -> EBounded
    }

    private fun add(a: EV, b: EV): EV = when {
        a is EF && b is EF -> if (a.exact != null && b.exact != null) EF.of(a.exact + b.exact) else EF(null, a.d + b.d)
        a is EInf && b is EInf -> if (a.sign == b.sign) a else if (a.sign == 0 || b.sign == 0) throw NeedSides() else throw Indet("∞−∞")
        a is EInf -> a
        b is EInf -> b
        else -> EBounded
    }

    private fun mulSign(a: Int, b: Int) = a * b

    private fun mul(a: EV, b: EV): EV = when {
        a is EF && b is EF -> if (a.exact != null && b.exact != null) EF.of(a.exact * b.exact) else EF(null, a.d * b.d)
        a is EF && b is EInf -> if (a.isZero) throw Indet("0·∞") else EInf(mulSign(a.sign, b.sign))
        a is EInf && b is EF -> mul(b, a)
        a is EInf && b is EInf -> EInf(mulSign(a.sign, b.sign))
        a is EF && b === EBounded -> if (a.isZero) { squeezed = true; EF.ZERO } else EBounded
        a === EBounded && b is EF -> mul(b, a)
        a === EBounded && b === EBounded -> EBounded
        else -> throw Indet("oscillation·∞")
    }

    private fun div(n: Node.Quotient): EV {
        val a = eval(n.num)
        val b = eval(n.den)
        return when (b) {
            is EF -> if (b.isZero) {
                when (a) {
                    is EF -> if (a.isZero) throw Indet("0/0") else EInf(a.sign * signOfZero(n.den))
                    is EInf -> EInf(a.sign * signOfZero(n.den))
                    EBounded -> throw Indet("bounded/0")
                }
            } else when (a) {
                is EF -> if (a.exact != null && b.exact != null) {
                    try { EF.of(a.exact / b.exact) } catch (_: UnsupportedMath) { EF(null, a.d / b.d) }
                } else EF(null, a.d / b.d)
                is EInf -> EInf(a.sign * b.sign)
                EBounded -> EBounded
            }
            is EInf -> when (a) {
                is EInf -> throw Indet("∞/∞")
                else -> EF.ZERO
            }
            EBounded -> throw Indet("÷ oscillation")
        }
    }

    // ------------------------------------------------------------------ signs of vanishing quantities

    private fun sampleSign(n: Node, s: Int): Int {
        var sg = 0
        for (k in intArrayOf(2, 3, 4)) {
            val x = if (pt.isInf) pt.inf * Math.pow(10.0, (k + 1).toDouble()) else pt.d + s * Math.pow(10.0, -k.toDouble())
            val y = try { NodeNum.eval(n, mapOf(v to x)) } catch (_: Undefined) { return 2 } catch (_: ArithmeticException) { return 2 }
            if (y.isNaN() || y == 0.0) return 2
            val t = if (y > 0) 1 else -1
            if (sg != 0 && sg != t) return 2
            sg = t
        }
        return sg
    }

    /** Sign of the quantity [n] (which tends to 0) next to the point: +1, -1, or 0 when the two sides differ. */
    private fun signOfZero(n: Node): Int {
        if (pt.isInf) return sampleSign(n, pt.inf).let { if (it == 2) throw Indet("a vanishing quantity with unclear sign") else it }
        val sides = if (side != 0) listOf(side) else listOf(1, -1)
        val ss = sides.map { sampleSign(n, it) }
        if (ss.any { it == 2 }) throw Indet("a vanishing quantity with unclear sign")
        return if (ss.all { it == ss[0] }) ss[0] else 0
    }

    // ------------------------------------------------------------------ powers, roots, functions

    private fun isIntegerValue(e: EF): Int? {
        val r = e.exact
        if (r != null) return if (r.isRational && r.rational.isInteger && r.rational.num.abs() <= java.math.BigInteger.valueOf(1000)) r.rational.num.toInt() else null
        val k = Math.rint(e.d)
        return if (Math.abs(e.d - k) < 1e-12 && Math.abs(k) < 1000) k.toInt() else null
    }

    private fun power(n: Node.Power): EV {
        val b = eval(n.base)
        val e = eval(n.exp)
        when (e) {
            is EF -> when (b) {
                is EF -> {
                    if (b.isZero) {
                        return when {
                            e.isZero -> throw Indet("0^0")
                            e.sign > 0 -> EF.ZERO
                            else -> {
                                val k = isIntegerValue(e)
                                if (k != null && k % 2 == 0) EInf(1) else EInf(signOfZero(n.base))
                            }
                        }
                    }
                    val k = isIntegerValue(e)
                    if (b.sign < 0 && k == null) throw Undefined("a negative base with a fractional exponent")
                    if (b.exact != null && e.exact != null && k != null) {
                        try { return EF.of(b.exact.pow(k)) } catch (_: UnsupportedMath) {}
                    }
                    if (b.exact != null && e.exact != null && e.exact.isRational && b.sign > 0) {
                        NumberTheory.exactPow(b.exact, e.exact.rational)?.let { return EF.of(it) }
                    }
                    val r = Math.pow(b.d, e.d)
                    if (r.isNaN() || r.isInfinite()) throw Undefined("the power is too large")
                    return EF(null, r)
                }
                is EInf -> {
                    if (e.isZero) throw Indet("∞^0")
                    if (e.sign < 0) return EF.ZERO
                    val k = isIntegerValue(e)
                    return when {
                        b.sign > 0 -> EInf(1)
                        k == null -> throw Undefined("a negative base with a fractional exponent")
                        k % 2 == 0 -> EInf(1)
                        else -> EInf(b.sign)
                    }
                }
                EBounded -> throw Indet("oscillation^c")
            }
            is EInf -> when (b) {
                is EF -> {
                    if (b.isZero) return if (e.sign > 0) EF.ZERO else EInf(1)
                    if (b.sign < 0) throw Undefined("a negative base with an infinite exponent")
                    val one = b.exact?.let { it == Surd.ONE } ?: (Math.abs(b.d - 1) < 1e-12)
                    if (one) throw Indet("1^∞")
                    val big = b.d > 1
                    return if (big == (e.sign > 0)) EInf(1) else EF.ZERO
                }
                is EInf -> {
                    if (b.sign <= 0) throw Undefined("a negative base with an infinite exponent")
                    return if (e.sign > 0) EInf(1) else EF.ZERO
                }
                EBounded -> throw Indet("oscillation^∞")
            }
            EBounded -> throw Indet("a power with an oscillating exponent")
        }
    }

    private fun root(n: Node.Root): EV {
        val a = eval(n.arg)
        return when (a) {
            is EF -> {
                if (a.sign < 0 && n.index % 2 == 0) throw Undefined("a root of a negative number")
                if (a.exact != null) {
                    val m = if (a.exact.signum() < 0) -a.exact else a.exact
                    val r: Surd? = if (n.index == 2) runCatching { m.sqrt() }.getOrNull()
                    else if (m.isRational) NumberTheory.exactRoot(m.rational, n.index)?.let { Surd.of(it) } else null
                    if (r != null) return EF.of(if (a.exact.signum() < 0) -r else r)
                }
                EF(null, Math.signum(a.d) * Math.pow(Math.abs(a.d), 1.0 / n.index))
            }
            is EInf -> when {
                a.sign > 0 -> EInf(1)
                n.index % 2 == 1 -> EInf(-1)
                a.sign == 0 && n.index % 2 == 0 -> EInf(1)
                else -> throw Undefined("a root of a negative number")
            }
            EBounded -> throw Indet("a root of an oscillation")
        }
    }

    private fun func(n: Node.Func): EV {
        val a = eval(n.arg)
        val baseSign: Int
        var lnBase = 1.0
        if (n.name == "ln") baseSign = 1 else {
            if (n.base!!.hasVariable()) throw UnsupportedMath("a logarithm with an unknown base")
            val bv = NodeNum.eval(n.base, emptyMap())
            if (bv <= 0 || bv == 1.0) throw Undefined("a logarithm whose base is not positive or is 1")
            lnBase = Math.log(bv)
            baseSign = if (lnBase > 0) 1 else -1
        }
        return when (a) {
            is EF -> when {
                a.isZero -> {
                    val sg = signOfZero(n.arg)
                    if (sg > 0) EInf(-baseSign) else if (sg == 0) throw Indet("a logarithm of something that changes sign") else throw Undefined("log of a negative number")
                }
                a.sign < 0 -> throw Undefined("log of a negative number")
                else -> {
                    val base = if (n.name == "ln") Surd.E_SURD else runCatching { NodeExact.eval(n.base!!, emptyMap()) }.getOrNull()
                    val ex = if (base != null && a.exact != null) NumberTheory.exactLog(base, a.exact) else null
                    if (ex != null) EF.of(Surd.of(ex)) else EF(null, Math.log(a.d) / lnBase)
                }
            }
            is EInf -> if (a.sign > 0) EInf(baseSign) else throw Undefined("log of a negative number")
            EBounded -> throw Indet("a logarithm of an oscillation")
        }
    }

    private fun trig(n: Node.Trig): EV {
        val a = eval(n.arg)
        val inverse = n.name.startsWith("arc")
        when (a) {
            is EF -> {
                val an = a.exact?.let { surdNode(it) } ?: Node.Number(Rational.parse(BigDecimal(a.d).toPlainString()))
                val call = Node.Trig(n.name, an, n.deg)
                try {
                    return EF.of(NodeExact.eval(call, emptyMap()))
                } catch (_: NotExact) {
                } catch (_: UnsupportedMath) {
                } catch (u: Undefined) {
                    if (inverse) throw u
                    val sg = signOfZeroOrPole(n)
                    return EInf(sg)
                }
                val x = try { NodeNum.eval(call, emptyMap()) } catch (u: Undefined) {
                    if (inverse) throw u
                    return EInf(signOfZeroOrPole(n))
                }
                return EF(null, x)
            }
            is EInf -> return when (n.name) {
                "sin", "cos" -> EBounded
                "arctan" -> EF.of(Surd.PI_SURD * Surd.of(Rational.of(a.sign.toLong(), 2)))
                else -> if (inverse) throw Undefined("an inverse function outside its domain") else throw Indet("an unbounded oscillation")
            }
            EBounded -> throw Indet("a function of an oscillation")
        }
    }

    private fun signOfZeroOrPole(n: Node.Trig): Int = signOfZero(n)
}

/** Leading behaviour of an algebraic expression as the variable goes to ±∞: `c·t^d` with x = s·t, t → +∞. */
internal class Lead(private val v: Char, private val s: Int) {
    private fun powC(c: Surd, r: Rational): Surd {
        if (r.isInteger) return c.pow(r.num.toInt())
        if (c.signum() < 0) throw Undefined("a fractional power of a negative number")
        return NumberTheory.exactPow(c, r) ?: throw NotAlgebraic()
    }

    fun of(n: Node): Pair<Surd, Rational>? = try { calc(n) } catch (_: UnsupportedMath) { throw NotAlgebraic() } catch (_: ArithmeticException) { throw NotAlgebraic() }

    private fun calc(n: Node): Pair<Surd, Rational>? = when (n) {
        is Node.Number -> if (n.value.isZero) null else Surd.of(n.value) to Rational.ZERO
        is Node.Const -> (if (n.symbol == 'π') Surd.PI_SURD else Surd.E_SURD) to Rational.ZERO
        is Node.Variable -> if (n.name == v) Surd.of(s.toLong()) to Rational.ONE else throw NotAlgebraic()
        is Node.Neg -> calc(n.arg)?.let { -it.first to it.second }
        is Node.Product -> {
            var c = Surd.ONE
            var d = Rational.ZERO
            var zero = false
            for (f in n.factors) {
                val x = calc(f)
                if (x == null) zero = true else { c *= x.first; d += x.second }
            }
            if (zero) null else c to d
        }
        is Node.Quotient -> {
            val a = calc(n.num)
            val b = calc(n.den) ?: throw Undefined("a denominator is 0")
            a?.let { (it.first / b.first) to (it.second - b.second) }
        }
        is Node.Power -> {
            if (n.exp.variableNames().isNotEmpty()) throw NotAlgebraic()
            val e = try { NodeExact.eval(n.exp, emptyMap()) } catch (_: NotExact) { throw NotAlgebraic() }
            if (!e.isRational) throw NotAlgebraic()
            val r = e.rational
            val b = calc(n.base)
            if (b == null) { if (r.signum <= 0) throw Undefined("0 to a negative power"); null } else powC(b.first, r) to b.second * r
        }
        is Node.Root -> calc(n.arg)?.let { b ->
            val c = if (n.index == 2) {
                if (b.first.signum() < 0) throw Undefined("a root of a negative number")
                b.first.sqrt()
            } else powC(b.first, Rational.of(1, n.index.toLong()))
            c to b.second / Rational.of(n.index.toLong())
        }
        is Node.Abs -> calc(n.arg)?.let { (if (it.first.signum() < 0) -it.first else it.first) to it.second }
        is Node.Sum -> {
            val parts = n.terms.mapNotNull { calc(it) }
            if (parts.isEmpty()) null else {
                val top = parts.maxOf { it.second }
                var c = Surd.ZERO
                for (p in parts) if (p.second.compareTo(top) == 0) c += p.first
                if (c.isZero) throw Cancel()
                c to top
            }
        }
        is Node.Func, is Node.Trig -> throw NotAlgebraic()
    }
}

/** High-precision evaluation for trees made only of + − × ÷, integer powers, √ and |.|; null when anything else appears. */
internal object NodeBig {
    private val mc = MathContext(70)

    fun eval(n: Node, x: BigDecimal, v: Char, others: Map<Char, BigDecimal> = emptyMap()): BigDecimal? = try { go(n, x, v, others) } catch (_: ArithmeticException) { null }

    private fun go(n: Node, x: BigDecimal, v: Char, o: Map<Char, BigDecimal>): BigDecimal? = when (n) {
        is Node.Number -> BigDecimal(n.value.num).divide(BigDecimal(n.value.den), mc)
        is Node.Const -> if (n.symbol == 'π') Surd.PI else Surd.E
        is Node.Variable -> if (n.name == v) x else o[n.name]
        is Node.Neg -> go(n.arg, x, v, o)?.negate()
        is Node.Sum -> n.terms.fold(BigDecimal.ZERO as BigDecimal?) { a, t -> if (a == null) null else go(t, x, v, o)?.let { a.add(it, mc) } }
        is Node.Product -> n.factors.fold(BigDecimal.ONE as BigDecimal?) { a, t -> if (a == null) null else go(t, x, v, o)?.let { a.multiply(it, mc) } }
        is Node.Quotient -> {
            val d = go(n.den, x, v, o)
            val a = go(n.num, x, v, o)
            if (a == null || d == null || d.signum() == 0) null else a.divide(d, mc)
        }
        is Node.Power -> {
            val e = if (n.exp.variableNames().isEmpty()) runCatching { NodeNum.eval(n.exp, emptyMap()) }.getOrNull() else null
            val k = e?.let { Math.rint(it) }
            val b = go(n.base, x, v, o)
            if (b == null || k == null || Math.abs(e - k) > 1e-12 || Math.abs(k) > 400) null
            else if (k >= 0) b.pow(k.toInt(), mc) else if (b.signum() == 0) null else BigDecimal.ONE.divide(b.pow((-k).toInt(), mc), mc)
        }
        is Node.Root -> if (n.index != 2) null else go(n.arg, x, v, o)?.let { if (it.signum() < 0) null else it.sqrt(mc) }
        is Node.Abs -> go(n.arg, x, v, o)?.abs()
        is Node.Func, is Node.Trig -> null
    }
}
