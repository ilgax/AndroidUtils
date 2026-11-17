package dev.ilgax.utils.math.solver

import java.math.BigInteger

/** Shared exact machinery of the sum and sequence solvers: polynomials in one letter, Bernoulli numbers, factoring, closed forms. */

private fun big(n: Int) = BigInteger.valueOf(n.toLong())

/** Polynomial in one letter with exact rational coefficients; c[i] belongs to n^i. */
internal class UPoly private constructor(val c: List<Rational>) {
    val isZero get() = c.isEmpty()
    val degree get() = c.size - 1
    val lead: Rational get() = c.last()
    fun coeff(i: Int) = c.getOrElse(i) { Rational.ZERO }

    operator fun plus(o: UPoly) = of((0..maxOf(degree, o.degree)).map { coeff(it) + o.coeff(it) })
    operator fun unaryMinus() = of(c.map { -it })
    operator fun minus(o: UPoly) = this + (-o)
    operator fun times(k: Rational) = of(c.map { it * k })
    operator fun times(o: UPoly): UPoly {
        if (isZero || o.isZero) return ZERO
        val r = Array(degree + o.degree + 1) { Rational.ZERO }
        for (i in c.indices) for (j in o.c.indices) r[i + j] += c[i] * o.c[j]
        return of(r.toList())
    }

    fun eval(x: Rational): Rational {
        var r = Rational.ZERO
        for (i in c.indices.reversed()) r = r * x + c[i]
        return r
    }

    /** this(q(n)). */
    fun compose(q: UPoly): UPoly {
        var r = ZERO
        for (i in c.indices.reversed()) r = r * q + const(c[i])
        return r
    }

    /** Exact quotient, or null when [d] does not divide this. */
    fun divExact(d: UPoly): UPoly? {
        if (d.isZero) return null
        var rem = this
        val q = Array(maxOf(degree - d.degree + 1, 0)) { Rational.ZERO }
        while (!rem.isZero && rem.degree >= d.degree) {
            val k = rem.lead / d.lead
            val at = rem.degree - d.degree
            q[at] = k
            rem -= (d * k).shift(at)
        }
        return if (rem.isZero) of(q.toList()) else null
    }

    fun shift(by: Int) = if (isZero) this else of(List(by) { Rational.ZERO } + c)
    override fun equals(other: Any?) = other is UPoly && c == other.c
    override fun hashCode() = c.hashCode()

    companion object {
        val ZERO = UPoly(emptyList())
        val N = UPoly(listOf(Rational.ZERO, Rational.ONE))
        fun of(l: List<Rational>): UPoly {
            var e = l.size
            while (e > 0 && l[e - 1].isZero) e--
            return UPoly(l.subList(0, e).toList())
        }
        fun const(r: Rational) = of(listOf(r))
        fun ints(vararg v: Long) = of(v.map { Rational.of(it) })
    }
}

/** Bernoulli numbers (B1 = +1/2, the convention of the sum formulas) and the power sums they give. */
internal object Bernoulli {
    private val cache = ArrayList<Rational>().apply { add(Rational.ONE) }

    private fun binom(n: Int, k: Int): BigInteger {
        var r = BigInteger.ONE
        for (i in 1..k) r = r.multiply(big(n - k + i)).divide(big(i))
        return r
    }

    fun number(m: Int): Rational {
        synchronized(cache) {
            while (cache.size <= m) {
                val k = cache.size
                var s = Rational.ZERO
                for (j in 0 until k) s += Rational.of(binom(k + 1, j)) * cache[j]
                cache.add(-s / Rational.of(k + 1L))
            }
            val b = cache[m]
            return if (m == 1) -b else b
        }
    }

    private val sums = HashMap<Int, UPoly>()

    /** 1^p + 2^p + … + m^p as a polynomial in m (Faulhaber); p = 0 gives m. */
    fun powerSum(p: Int): UPoly = synchronized(sums) {
        sums.getOrPut(p) {
            val coef = Array(p + 2) { Rational.ZERO }
            for (j in 0..p) coef[p + 1 - j] = Rational.of(binom(p + 1, j)) * number(j) / Rational.of(p + 1L)
            UPoly.of(coef.toList())
        }
    }
}

/** content · Π factor^multiplicity, every factor a primitive integer polynomial with positive leading coefficient. */
internal class Factored(val content: Rational, val factors: List<Pair<UPoly, Int>>)

internal object Factoring {
    private fun divisors(v: BigInteger): List<BigInteger>? {
        if (v.bitLength() > 40) return null
        val n = v.toLong()
        val out = ArrayList<BigInteger>()
        var i = 1L
        while (i * i <= n) {
            if (n % i == 0L) { out += BigInteger.valueOf(i); if (i != n / i) out += BigInteger.valueOf(n / i) }
            i++
        }
        return out
    }

    private fun findRoot(p: UPoly): Rational? {
        val ps = divisors(p.c[0].num.abs()) ?: return null
        val qs = divisors(p.lead.num.abs()) ?: return null
        for (a in ps) for (b in qs) for (s in intArrayOf(1, -1)) {
            val r = Rational.of(a.multiply(big(s)), b)
            if (p.eval(r).isZero) return r
        }
        return null
    }

    fun factor(p0: UPoly): Factored {
        if (p0.isZero) return Factored(Rational.ZERO, emptyList())
        var l = BigInteger.ONE
        for (x in p0.c) l = l.multiply(x.den).divide(l.gcd(x.den))
        val ints = p0.c.map { it.num.multiply(l).divide(it.den) }
        var g = BigInteger.ZERO
        for (x in ints) g = g.gcd(x)
        var prim = UPoly.of(ints.map { Rational.of(it.divide(g)) })
        var content = Rational.of(g, l)
        if (prim.lead.signum < 0) { prim = -prim; content = -content }
        val out = ArrayList<Pair<UPoly, Int>>()
        var z = 0
        while (prim.c[0].isZero) { prim = UPoly.of(prim.c.drop(1)); z++ }
        if (z > 0) out += UPoly.N to z
        while (prim.degree >= 1) {
            val root = findRoot(prim) ?: break
            val lin = UPoly.of(listOf(Rational.of(root.num.negate()), Rational.of(root.den)))
            var m = 0
            while (true) { prim = prim.divExact(lin) ?: break; m++ }
            out += lin to m
        }
        if (prim.degree >= 1) out += prim to 1 else content *= prim.c[0]
        return Factored(content, out)
    }
}

/** Removes spaces, keeping the one after a `\command` that is followed by a letter (`\cdot k`). */
internal fun compactTex(s: String): String {
    val out = StringBuilder()
    var inCommand = false
    var pendingSpace = false
    for (c in s) {
        if (c.isWhitespace()) { pendingSpace = inCommand; continue }
        if (pendingSpace && c.isLetter()) out.append(' ')
        pendingSpace = false
        if (c == '\\') inCommand = true else if (!(inCommand && c.isLetter())) inCommand = false
        out.append(c)
    }
    return out.toString()
}

/** A piece of LaTeX with its plain-text twin. */
internal class Fmt(val tex: String, val plain: String) {
    override fun toString() = tex

    companion object {
        private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹"
        fun sup(e: String) = if (e.all { it.isDigit() }) e.map { SUP[it - '0'] }.joinToString("") else "^($e)"

        private fun topLevelSign(s: String): Boolean {
            var d = 0
            for ((i, ch) in s.withIndex()) {
                if (ch == '(') d++ else if (ch == ')') d--
                else if (d == 0 && i > 0 && (ch == '+' || ch == '-')) return true
            }
            return false
        }

        fun of(s: String) = Fmt(s, s)
        fun rat(q: Rational) = Fmt(Printer.rational(q), plainOf(Printer.rational(q)))
        fun frac(a: Fmt, b: Fmt): Fmt {
            val num = if (topLevelSign(a.plain)) "(${a.plain})" else a.plain
            val den = if (b.plain.all { it.isLetterOrDigit() || it == '.' || it == '^' || it in SUP }) b.plain else "(${b.plain})"
            return Fmt("\\frac{${a.tex}}{${b.tex}}", "$num/$den")
        }
        fun pow(base: Fmt, e: Int, parens: Boolean): Fmt {
            val bt = if (base.tex.contains("\\frac")) "\\left(${base.tex}\\right)" else if (parens) "(${base.tex})" else base.tex
            val bp = if (parens || base.plain.contains('/')) "(${base.plain})" else base.plain
            return Fmt("$bt^{$e}", bp + sup(e.toString()))
        }
        /** `x^{e}` where the exponent is LaTeX text such as `n+1`. */
        fun powText(base: String, e: String) = Fmt("$base^{$e}", base + (if (e.all { it.isDigit() }) sup(e) else if (e.length == 1) "^$e" else "^($e)"))
    }

    operator fun plus(o: Fmt) = Fmt(tex + o.tex, plain + o.plain)
}

/** Printing of polynomials, factored polynomials and closed forms. */
internal object SeriesFmt {
    /** Integer coefficients only. */
    private fun intPoly(p: UPoly, v: String): Fmt {
        val tex = StringBuilder()
        val plain = StringBuilder()
        var first = true
        for (d in p.degree downTo 0) {
            val k = p.coeff(d).num
            if (k.signum() == 0) continue
            val neg = k.signum() < 0
            val a = k.abs()
            tex.append(if (neg) "-" else if (first) "" else "+")
            plain.append(if (neg) "-" else if (first) "" else "+")
            val coef = if (a == BigInteger.ONE && d > 0) "" else a.toString()
            tex.append(coef); plain.append(coef)
            if (d >= 1) { tex.append(v); plain.append(v) }
            if (d >= 2) { tex.append("^{$d}"); plain.append(Fmt.sup(d.toString())) }
            first = false
        }
        return Fmt(tex.toString(), plain.toString())
    }

    /** A polynomial with rational coefficients: one common denominator, (n²+3n)/2. */
    fun poly(p: UPoly, v: String): Fmt {
        if (p.isZero) return Fmt.of("0")
        var l = BigInteger.ONE
        for (x in p.c) l = l.multiply(x.den).divide(l.gcd(x.den))
        if (l == BigInteger.ONE) return intPoly(p, v)
        val scaled = p * Rational.of(l)
        val neg = scaled.lead.signum < 0
        val body = Fmt.frac(intPoly(if (neg) -scaled else scaled, v), Fmt.of(l.toString()))
        return if (neg) Fmt("-" + body.tex, "-" + body.plain) else body
    }

    private fun isSquare(q: Rational): Rational? {
        val r = NumberTheory.exactRoot(q.abs(), 2) ?: return null
        return if (q.signum >= 0) r else null
    }

    /** A product of the factors of [f] (and the [lead] symbol), without the denominator and sign. */
    private fun product(num: BigInteger, lead: String?, fs: List<Pair<UPoly, Int>>, v: String, divide: Int): Fmt {
        val tex = StringBuilder()
        val plain = StringBuilder()
        if (num != BigInteger.ONE || (fs.isEmpty() && lead == null)) { tex.append(num); plain.append(num) }
        if (lead != null) { tex.append(lead); plain.append(lead) }
        val order = fs.sortedWith(compareBy({ it.first.degree > 1 }, { it.first.lead.num }, { it.first.c[0].num.abs() }))
        for ((p, m) in order) {
            val body = intPoly(p, v)
            val atom = p.c.count { !it.isZero } == 1
            val e = m / divide
            val b = if (atom || (order.size == 1 && num == BigInteger.ONE && lead == null && divide == 1 && m == 1)) body else Fmt("(${body.tex})", "(${body.plain})")
            val f = if (e > 1) Fmt("${b.tex}^{$e}", b.plain + Fmt.sup(e.toString())) else b
            tex.append(f.tex); plain.append(f.plain)
        }
        return Fmt(tex.toString(), plain.toString())
    }

    /** n(n+1)(2n+1)/6, (n(n+1)/2)², n²: the factored form of a polynomial. [lead] is a symbol multiplied in front. */
    fun factored(p: UPoly, v: String, lead: String? = null): Fmt {
        val f = Factoring.factor(p)
        if (f.content.isZero) return Fmt.of("0")
        if (f.factors.isEmpty()) {
            val r = Fmt.rat(f.content)
            if (lead == null) return r
            return if (f.content == Rational.ONE) Fmt.of(lead) else if (f.content.isInteger) Fmt(f.content.num.toString() + lead, f.content.num.toString() + lead) else Fmt(Printer.rational(f.content) + lead, plainOf(Printer.rational(f.content)) + lead)
        }
        val neg = f.content.signum < 0
        val c = f.content.abs()
        val sq = isSquare(c)
        val square = sq != null && f.factors.all { it.second % 2 == 0 } && lead == null
        val body: Fmt = if (square) {
            val g = if (f.factors.size == 1 && sq == Rational.ONE) {
                // a single factor: n^2 or (n+1)^2
                val (poly, m) = f.factors[0]
                val b = intPoly(poly, v)
                return sign(neg, if (poly.c.count { !it.isZero } == 1) Fmt("${b.tex}^{$m}", b.plain + Fmt.sup(m.toString())) else Fmt("(${b.tex})^{$m}", "(${b.plain})" + Fmt.sup(m.toString())))
            } else {
                val top = product(sq!!.num, null, f.factors, v, 2)
                if (sq.den == BigInteger.ONE) top else Fmt.frac(top, Fmt.of(sq.den.toString()))
            }
            Fmt.pow(g, 2, false)
        } else {
            val top = product(c.num, lead, f.factors, v, 1)
            if (c.den == BigInteger.ONE) top else Fmt.frac(top, Fmt.of(c.den.toString()))
        }
        return sign(neg, body)
    }

    private fun sign(neg: Boolean, f: Fmt) = if (neg) Fmt("-" + f.tex, "-" + f.plain) else f

    private fun expBody(base: Rational, v: String, shift: Int = 0): Fmt {
        val e = if (shift == 0) v else "$v+$shift"
        val b = if (base.isInteger) (if (base.signum < 0) "(${base.num})" else base.num.toString()) else "(${Printer.rational(base)})"
        val bp = if (base.isInteger) (if (base.signum < 0) "(${base.num})" else base.num.toString()) else "(${base.num}/${base.den})"
        return Fmt("$b^{$e}", bp + (if (e.length == 1) "^$e" else "^($e)"))
    }

    /** A closed form of a sum, factored where it is a polynomial. [lead] is a symbol multiplied in front (a constant c). */
    fun closed(cf: Closed, v: String, lead: String? = null): Fmt {
        if (cf.exps.isEmpty()) return factored(cf.poly, v, lead)
        val intBases = cf.exps.filter { it.key.isInteger }
        val fracBases = cf.exps.filter { !it.key.isInteger }
        var l = BigInteger.ONE
        for (x in cf.poly.c) l = l.multiply(x.den).divide(l.gcd(x.den))
        for (x in intBases.values) l = l.multiply(x.den).divide(l.gcd(x.den))
        val L = Rational.of(l)
        // (sign, text) items of the numerator
        val items = ArrayList<Triple<Boolean, String, String>>()
        for ((b, k) in intBases.entries.sortedByDescending { it.key.abs() }) {
            val m = k * L
            val neg = m.signum < 0
            val a = m.num.abs()
            val body: Fmt = if (a == BigInteger.ONE) expBody(b, v) else {
                var j = 0
                var t = a
                if (b.signum > 0) while (t.mod(b.num) == BigInteger.ZERO && t > BigInteger.ONE) { t = t.divide(b.num); j++ }
                if (b.signum > 0 && t == BigInteger.ONE && j >= 1) expBody(b, v, j) else {
                    val eb = expBody(b, v)
                    Fmt("$a\\cdot ${eb.tex}", "$a${eb.plain.let { s -> if (s[0].isDigit()) "·$s" else s }}")
                }
            }
            items += Triple(neg, body.tex, body.plain)
        }
        val scaled = cf.poly * L
        for (d in scaled.degree downTo 0) {
            val k = scaled.coeff(d).num
            if (k.signum() == 0) continue
            val a = k.abs()
            val coef = if (a == BigInteger.ONE && d > 0) "" else a.toString()
            val tex = coef + (if (d >= 1) v else "") + (if (d >= 2) "^{$d}" else "")
            val plain = coef + (if (d >= 1) v else "") + (if (d >= 2) Fmt.sup(d.toString()) else "")
            items += Triple(k.signum() < 0, tex, plain)
        }
        val tex = StringBuilder()
        val plain = StringBuilder()
        for ((i, it) in items.withIndex()) {
            val s = if (it.first) "-" else if (i == 0) "" else "+"
            tex.append(s).append(it.second); plain.append(s).append(it.third)
        }
        var main = Fmt(tex.toString(), plain.toString())
        if (l != BigInteger.ONE) main = Fmt.frac(main, Fmt.of(l.toString()))
        if (lead != null) main = if (items.size > 1 && l == BigInteger.ONE) Fmt("$lead(${main.tex})", "$lead(${main.plain})") else Fmt(lead + main.tex, lead + main.plain)
        // exponentials with a fractional base: 1/2^n, written as their own terms
        val sb = StringBuilder(main.tex)
        val sp = StringBuilder(main.plain)
        val empty = items.isEmpty()
        for ((b, k) in fracBases.entries.sortedBy { it.key }) {
            val neg = k.signum < 0
            val a = k.abs()
            val term: Fmt = if (b.num == BigInteger.ONE) {
                val den = (if (a.den == BigInteger.ONE) "" else "${a.den}\\cdot ") + "${b.den}^{$v}"
                val denPlain = (if (a.den == BigInteger.ONE) "" else "${a.den}·") + "${b.den}^$v"
                Fmt.frac(Fmt.of(a.num.toString()), Fmt(den, denPlain))
            } else {
                val eb = expBody(b, v)
                val coef = if (a == Rational.ONE) Fmt.of("") else Fmt.rat(a)
                Fmt(coef.tex + eb.tex, coef.plain + eb.plain)
            }
            val s = if (neg) "-" else if (empty && sb.isEmpty()) "" else "+"
            sb.append(s).append(term.tex); sp.append(s).append(term.plain)
        }
        return Fmt(sb.toString(), sp.toString())
    }
}

/** Polynomial part plus c·base^n terms: the closed form of a sum in the letter n. */
internal class Closed(val poly: UPoly, val exps: Map<Rational, Rational>) {
    operator fun plus(o: Closed): Closed {
        val m = LinkedHashMap(exps)
        for ((b, k) in o.exps) { val s = (m[b] ?: Rational.ZERO) + k; if (s.isZero) m.remove(b) else m[b] = s }
        return Closed(poly + o.poly, m)
    }

    operator fun times(k: Rational) = if (k.isZero) Closed(UPoly.ZERO, emptyMap()) else Closed(poly * k, exps.mapValues { it.value * k })

    /** The value at a whole number n. */
    fun eval(n: Long): Rational {
        var r = poly.eval(Rational.of(n))
        for ((b, k) in exps) r += k * powInt(b, n)
        return r
    }

    /** The value when no letter is left. */
    fun value(): Rational = poly.eval(Rational.ZERO)

    companion object {
        fun powInt(b: Rational, e: Long): Rational {
            if (Math.abs(e) > 5000) throw UnsupportedMath("a power with an exponent beyond 5000")
            if (b.isZero && e < 0) throw ArithmeticException("division by zero")
            return b.pow(e.toInt())
        }
    }
}

// ------------------------------------------------------------------ expressions in the index: Σ c·k^p·R^k

internal class NotClosed(val why: String) : Exception(why, null, false, false)

internal data class KKey(val p: Int, val r: Rational)

/** A function of the index as a sum of c·k^p·R^k. */
internal class KForm(val t: Map<KKey, Rational>) {
    operator fun plus(o: KForm): KForm {
        val m = LinkedHashMap(t)
        for ((k, c) in o.t) { val s = (m[k] ?: Rational.ZERO) + c; if (s.isZero) m.remove(k) else m[k] = s }
        return KForm(m)
    }

    operator fun unaryMinus() = KForm(t.mapValues { -it.value })

    operator fun times(o: KForm): KForm {
        var r = KForm(emptyMap())
        for ((k1, c1) in t) for ((k2, c2) in o.t) {
            val p = k1.p + k2.p
            if (p > 60) throw NotClosed("a power of the index above 60")
            r += KForm(mapOf(KKey(p, k1.r * k2.r) to c1 * c2))
        }
        if (r.t.size > 400) throw NotClosed("too many terms")
        return r
    }

    fun pow(m: Int): KForm {
        var r = KForm(mapOf(KKey(0, Rational.ONE) to Rational.ONE))
        repeat(m) { r *= this }
        return r
    }

    val constant: Rational? get() = if (t.isEmpty()) Rational.ZERO else if (t.keys.all { it.p == 0 && it.r == Rational.ONE }) t.values.first() else null

    /** a·k + b with a, b rational, or null. */
    fun affine(): Pair<Rational, Rational>? {
        if (t.keys.any { it.r != Rational.ONE || it.p > 1 }) return null
        return (t[KKey(1, Rational.ONE)] ?: Rational.ZERO) to (t[KKey(0, Rational.ONE)] ?: Rational.ZERO)
    }

    fun invert(): KForm {
        if (t.size != 1) throw NotClosed("a division by a sum that contains the index")
        val (k, c) = t.entries.first()
        if (k.p != 0) throw NotClosed("a division by the index")
        return KForm(mapOf(KKey(0, Rational.ONE / k.r) to Rational.ONE / c))
    }

    companion object {
        fun num(q: Rational) = KForm(if (q.isZero) emptyMap() else mapOf(KKey(0, Rational.ONE) to q))

        fun of(n: Node, v: Char): KForm = when (n) {
            is Node.Number -> num(n.value)
            is Node.Variable -> if (n.name == v) KForm(mapOf(KKey(1, Rational.ONE) to Rational.ONE)) else throw NotClosed("the letter ${n.name}")
            is Node.Neg -> -of(n.arg, v)
            is Node.Sum -> n.terms.map { of(it, v) }.reduce { a, b -> a + b }
            is Node.Product -> n.factors.map { of(it, v) }.reduce { a, b -> a * b }
            is Node.Quotient -> of(n.num, v) * of(n.den, v).let { if (it.t.isEmpty()) throw ArithmeticException("division by zero") else it.invert() }
            is Node.Power -> {
                val e = of(n.exp, v)
                val b = of(n.base, v)
                val ec = e.constant
                if (ec != null) {
                    if (!ec.isInteger || ec.num.abs() > BigInteger.valueOf(200)) throw NotClosed("a non-integer or huge power")
                    val m = ec.num.toInt()
                    if (m >= 0) b.pow(m) else b.invert().pow(-m)
                } else {
                    val (a, beta) = e.affine() ?: throw NotClosed("an exponent that is not linear in the index")
                    val c = b.constant ?: throw NotClosed("a base that depends on the index")
                    if (c.isZero || !a.isInteger || !beta.isInteger || a.num.abs() > BigInteger.valueOf(60) || beta.num.abs() > BigInteger.valueOf(400)) throw NotClosed("an exponent that is not a small whole-number multiple of the index")
                    KForm(mapOf(KKey(0, c.pow(a.num.toInt())) to c.pow(beta.num.toInt())))
                }
            }
            else -> throw NotClosed("a root, logarithm, constant or trigonometric function")
        }
    }
}

/** Exact rational evaluation of a node at given values of its letters. */
internal object RatEval {
    class NotRational : Exception(null, null, false, false)

    fun eval(n: Node, env: Map<Char, Rational>): Rational = when (n) {
        is Node.Number -> n.value
        is Node.Variable -> env[n.name] ?: throw NotRational()
        is Node.Neg -> -eval(n.arg, env)
        is Node.Sum -> n.terms.map { eval(it, env) }.reduce { a, b -> a + b }
        is Node.Product -> n.factors.map { eval(it, env) }.reduce { a, b -> a * b }
        is Node.Quotient -> {
            val d = eval(n.den, env)
            if (d.isZero) throw Undefined("a denominator is 0")
            eval(n.num, env) / d
        }
        is Node.Power -> {
            val b = eval(n.base, env)
            val e = eval(n.exp, env)
            if (!e.isInteger || e.num.abs() > BigInteger.valueOf(5000)) throw NotRational()
            val k = e.num.toInt()
            if (b.isZero) { if (k < 0) throw Undefined("0 to a negative power") else if (k == 0) Rational.ONE else Rational.ZERO }
            else {
                if ((maxOf(b.num.bitLength(), b.den.bitLength()).toLong()) * Math.abs(k) > 400_000L) throw NotRational()
                b.pow(k)
            }
        }
        is Node.Abs -> eval(n.arg, env).abs()
        else -> throw NotRational()
    }
}

/** Σ_{k=lo}^{hi} of a [KForm], where the bounds are a·n+b; null when a piece has no closed form here. */
internal object SumFormula {
    private fun powerOf(r: Rational, a: Rational, b: Rational): Pair<Rational, Rational>? {
        // r^(a·n+b) = r^b · (r^a)^n
        if (!a.isInteger || !b.isInteger || a.num.abs() > BigInteger.valueOf(200) || b.num.abs() > BigInteger.valueOf(5000)) return null
        return r.pow(a.num.toInt()) to r.pow(b.num.toInt())
    }

    private fun piece(key: KKey, coef: Rational, lo: UPoly, hi: UPoly): Closed? {
        if (key.r == Rational.ONE) {
            val s = Bernoulli.powerSum(key.p)
            return Closed(s.compose(hi) * coef - s.compose(lo - UPoly.const(Rational.ONE)) * coef, emptyMap())
        }
        if (key.p != 0) return null
        // c (R^(hi+1) - R^lo) / (R - 1)
        val k = coef / (key.r - Rational.ONE)
        var poly = UPoly.ZERO
        val exps = LinkedHashMap<Rational, Rational>()
        fun add(e: UPoly, sign: Int): Boolean {
            val (base, c) = powerOf(key.r, e.coeff(1), e.coeff(0)) ?: return false
            val v = k * c * Rational.of(sign.toLong())
            if (base == Rational.ONE) poly += UPoly.const(v)
            else { val s = (exps[base] ?: Rational.ZERO) + v; if (s.isZero) exps.remove(base) else exps[base] = s }
            return true
        }
        if (!add(hi + UPoly.const(Rational.ONE), 1) || !add(lo, -1)) return null
        return Closed(poly, exps)
    }

    fun closed(form: KForm, lo: UPoly, hi: UPoly): Closed? {
        var r = Closed(UPoly.ZERO, emptyMap())
        for ((k, c) in form.t) r += piece(k, c, lo, hi) ?: return null
        return r
    }

    fun pieceClosed(key: KKey, coef: Rational, lo: UPoly, hi: UPoly) = piece(key, coef, lo, hi)
}
