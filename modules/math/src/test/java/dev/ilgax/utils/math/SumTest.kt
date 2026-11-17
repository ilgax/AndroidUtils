package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.Rational
import dev.ilgax.utils.math.solver.SolveResult
import dev.ilgax.utils.math.solver.SumHooks
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finite sums: formulas, notation variants, equations, the fallback and the numeric safety check. */
class SumTest {
    private fun plain(raw: String) = answerOf(raw)

    @Test fun numericSums() {
        assertEquals("670", plain("\\sum_{k=1}^{20}(3k+2)"))
        assertEquals("1023", plain("\\sum_{k=0}^{9}2^k"))
        assertEquals("5050", plain("\\sum_{k=1}^{100}k"))
        assertEquals("385", plain("\\sum_{k=1}^{10}k^2"))
        assertEquals("28", plain("\\sum_{k=-3}^{3}k^2"))
        assertEquals("0", plain("\\sum_{k=-3}^{3}k"))
        assertEquals("5", plain("\\sum_{k=1}^{5}1"))
        assertEquals("63/32 ≈ 1.97", plain("\\sum_{k=0}^{5}\\frac{1}{2^k}"))
        assertEquals("121", plain("\\sum_{k=0}^{4}3^k"))
        assertEquals("-1", plain("\\sum_{k=0}^{1}(-2)^k"))
    }

    @Test fun everyPowerThroughBernoulli() {
        for (p in 0..12) {
            var want = BigInteger.ZERO
            for (k in 1..10) want += BigInteger.valueOf(k.toLong()).pow(p)
            assertEquals("p=$p", want.toString(), plain("\\sum_{k=1}^{10}k^{$p}"))
            // the symbolic formula is checked against direct sums by the solver itself
            solved("\\sum_{k=1}^{n}k^{$p}")
        }
    }

    @Test fun symbolicBounds() {
        assertEquals("n(n+1)/2", plain("\\sum_{k=1}^{n}k"))
        assertEquals("n(n+1)(2n+1)/6", plain("\\sum_{k=1}^{n}k^2"))
        assertEquals("(n(n+1)/2)²", plain("\\sum_{k=1}^{n}k^3"))
        assertEquals("n(n+1)(2n+1)(3n²+3n-1)/30", plain("\\sum_{k=1}^{n}k^4"))
        assertEquals("n²", plain("\\sum_{k=1}^{n}(2k-1)"))
        assertEquals("(3^n-1)/2", plain("\\sum_{k=0}^{n-1}3^k"))
        assertEquals("1-1/2^n", plain("\\sum_{k=1}^{n}\\frac{1}{2^k}"))
        assertEquals("2^(n+1)-2", plain("\\sum_{k=1}^{n}2^k"))
        assertEquals("n(2n+1)", plain("\\sum_{j=1}^{2n}j"))
        assertEquals("(n+1)(n+2)/2", plain("\\sum_{k=1}^{n+1}k"))
        assertEquals("n(2n²+5n+5)/2", plain("\\sum_{r=1}^{n}(3r^2+2r+1)"))
        assertEquals("cn", plain("\\sum_{k=1}^{n}c"))
        assertEquals("5c", plain("\\sum_{k=1}^{5}c"))
        assertEquals("n+1", plain("\\sum_{k=0}^{n}1"))
        assertEquals("n", plain("\\sum_{k=1}^{n}1^k"))
        assertEquals("n(n-1)/2", plain("\\sum_{k=1}^{n-1}k"))
        // the latex of the main line
        assertEquals("\\sum_{k=1}^{n}k^{2}=\\frac{n(n+1)(2n+1)}{6}", latexOf("\\sum_{k=1}^{n}k^2"))
        assertEquals("\\sum_{k=1}^{n}k^{3}=\\left(\\frac{n(n+1)}{2}\\right)^{2}", latexOf("\\sum_{k=1}^{n}k^3"))
    }

    @Test fun stepsShowLinearityAndTheFormula() {
        val r = solved("\\sum_{k=1}^{20}(3k+2)")
        val caps = r.captions()
        assertTrue(caps.toString(), caps.any { it.contains("linearity") })
        assertTrue(caps.toString(), caps.any { it.contains("Power sum") })
        assertTrue(caps.toString(), caps.any { it.contains("constant") })
        assertTrue(r.steps.any { it.latex.contains("3\\sum_{k=1}^{20}k+2\\sum_{k=1}^{20}1") })
        val g = solved("\\sum_{k=1}^{n}2^k").captions()
        assertTrue(g.toString(), g.any { it.contains("Geometric sum") })
        val s = solved("\\sum_{k=1}^{n}(2k-1)").captions()
        assertTrue(s.toString(), s.contains("Simplify") && s.contains("Add the pieces"))
    }

    @Test fun notationVariants() {
        val want = "n(n+1)(2n+1)/6"
        for (raw in listOf(
            "\\sum\\limits_{k=1}^{n}k^2", "\\displaystyle\\sum_{k=1}^{n}k^2", "\\sum_{k=1}^{n}{k^2}", "\\sum_{k = 1}^{n} k^{2}",
            "\\sum_{k=1^{n}k^2", "\\sum_{k=1}^{n}{k^2", "\\sum_k=1^nk^2", "\\sum_{i=1}^{n}i^2", "\\sum_{j=1}^nj^2", "\\sum_{r=1}^{n}r^{2}",
            "\\displaystyle \\sum\\limits_{i=1}^{n} i^{2}", "\\sum_{k=1}^{n}k^2=", "\\left(\\sum_{k=1}^{n}k^2\\right)".removePrefix("\\left(").removeSuffix("\\right)"),
        )) assertEquals(raw, want, plain(raw))
    }

    @Test fun emptyAndEdgeCases() {
        val r = solved("\\sum_{k=5}^{3}k")
        assertEquals("0", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.contains("Empty sum") })
        assertEquals("0", plain("\\sum_{k=1}^{0}k^2"))
        assertEquals("1", plain("\\sum_{k=1}^{1}k"))
        assertEquals("-3", plain("\\sum_{k=-3}^{-3}k"))
        assertEquals("6", plain("\\sum_{k=-3}^{-1}(-k)"))
        assertEquals("cn", plain("\\sum_{k=1}^{n}c"))
        // lower bound other than 1
        assertEquals("(n-2)(n+3)/2", plain("\\sum_{k=3}^{n}k"))
        // r = 1 geometric
        assertEquals("10", plain("\\sum_{k=1}^{10}1^k"))
        assertEquals("30", plain("\\sum_{k=1}^{10}3\\cdot 1^k"))
        // the notation of the user: raised dot multiplies, a colon divides
        assertEquals("670", plain("\\sum_{k=1}^{20}(3\\cdot k+2)"))
        assertEquals("55", plain("\\sum_{k=1}^{10}k"))
        assertEquals("\\sum_{k=1}^{20}(3k+2)", LatexNormalizer.normalize("\\sum_{k=1}^{20}(3k+2)").latex.removeSuffix("=670"))
    }

    @Test fun equations() {
        assertEquals("n = 10", plain("\\sum_{k=1}^{n}k=55"))
        assertEquals("n = 10", plain("55=\\sum_{k=1}^{n}k"))
        assertEquals("n = 10", plain("\\sum_{k=1}^{n}k^2=385"))
        assertEquals("n = 5", plain("\\sum_{k=1}^{n}2^k=62"))
        assertEquals("n = 5", plain("\\sum_{k=3}^{n}k=12"))
        assertEquals("n = 100", plain("\\sum_{k=1}^{n}(2k-1)=10000"))
        assertEquals("No solution", plain("\\sum_{k=1}^{n}k=56"))
        assertEquals("No solution", plain("\\sum_{k=1}^{n}k=-3"))
        val r = solved("\\sum_{k=1}^{n}k=55")
        // the negative root is rejected and the rule is stated
        assertTrue(r.notes.toString(), r.notes.any { it.contains("natural numbers") })
        assertTrue(r.steps.toString(), r.steps.any { it.caption.startsWith("Rejected") && it.caption.contains("natural number") })
        // n = 5/2 is not a whole number
        val h = solved("\\sum_{k=1}^{n}k=\\frac{15}{8}")
        assertEquals("No solution", h.answers[0].plain)
        assertTrue(h.steps.toString(), h.steps.any { it.caption.contains("not a whole number") })
        // a root below the lower bound is rejected: sum_{k=3}^{n} k = 3 gives n = 3 or n = -3 -> only 3 is >= 3
        assertEquals("n = 3", plain("\\sum_{k=3}^{n}k=3"))
        // sum_{k=3}^{n} k = 0 would need n = 2 or n = -3, below the lower bound
        assertEquals("No solution", plain("\\sum_{k=3}^{n}k=0"))
    }

    @Test fun fallbackAddsTheTerms() {
        assertEquals("25/12 ≈ 2.08", plain("\\sum_{k=1}^{4}\\frac{1}{k}"))
        assertEquals("1/2 = 0.5", plain("\\sum_{k=1}^{1}\\frac{1}{k(k+1)}"))
        assertEquals("10/11 ≈ 0.91", plain("\\sum_{k=1}^{10}\\frac{1}{k(k+1)}"))
        // a long harmonic sum: a decimal, with a note
        val big = solved("\\sum_{k=1}^{100}\\frac{1}{k}")
        assertTrue(big.answers[0].plain, big.answers[0].plain.startsWith("≈ 5.1874"))
        assertTrue(big.notes.toString(), big.notes.any { it.contains("decimal") })
        // too many terms, or symbolic bounds: a clear message
        assertTrue(failedReason("\\sum_{k=1}^{20000}\\frac{1}{k}").contains("closed form for this sum isn't supported; infinite sums come later"))
        assertTrue(failedReason("\\sum_{k=1}^{n}\\frac{1}{k}").contains("closed form for this sum isn't supported; infinite sums come later"))
        assertTrue(failedReason("\\sum_{k=1}^{n}\\sqrt{k}").contains("isn't supported"))
        assertTrue(failedReason("\\sum_{k=1}^{n}\\frac{1}{k}=2").contains("isn't supported"))
        // exactly 10000 terms still work
        assertTrue(SolvePipeline.run("\\sum_{k=1}^{10000}\\frac{1}{k}", DotMeaning.Multiply) is SolveResult.Solved)
    }

    @Test fun infiniteSumsAreUnsupportedForNow() {
        for (raw in listOf("\\sum_{k=1}^{\\infty}\\frac{1}{2^k}", "\\sum_{n=0}^{\\infty}\\frac{1}{n^2}", "\\sum_{k=1}^{∞}k")) {
            val r = SolvePipeline.run(raw, DotMeaning.Multiply)
            assertTrue("$raw: $r", r is SolveResult.Failed)
            val reason = (r as SolveResult.Failed).reason
            assertTrue(reason, reason.contains("infinite series") && reason.contains("planned"))
        }
    }

    @Test fun otherUnsupported() {
        assertTrue(failedReason("\\sum_{k=1}^{n}\\sum_{j=1}^{k}j").contains("sum inside a sum"))
        assertTrue(failedReason("\\sum_{k=1}^{n}k=m").contains("isn't supported"))
        assertTrue(failedReason("\\sum_{k=1}^{n}k^2+\\sum_{k=1}^{n}k").contains("sum inside a sum"))
        assertTrue(failedReason("\\sum_{k=1}^{m+n}k").contains("more than one letter"))
        assertTrue(failedReason("\\sum_{k=1}^{n}(k+x)").contains("isn't supported"))
        assertTrue(failedReason("\\sum_{k=1}^{\\frac{n}{2}}k").contains("isn't supported"))
    }

    @Test fun everyOutputIsWellFormedLatex() {
        for (raw in listOf(
            "\\sum_{k=1}^{20}(3k+2)", "\\sum_{k=0}^{9}2^k", "\\sum_{k=1}^{n}k^2", "\\sum_{k=1}^{n}k^3", "\\sum_{k=1}^{n}(2k-1)", "\\sum_{k=0}^{n-1}3^k",
            "\\sum_{k=1}^{n}\\frac{1}{2^k}", "\\sum_{k=1}^{n}k=55", "\\sum_{k=1}^{n}c", "\\sum_{k=5}^{3}k", "\\sum_{k=1}^{4}\\frac{1}{k}", "\\sum_{k=1}^{100}\\frac{1}{k}",
            "\\sum_{k=1}^{n}k^5", "\\sum_{k=1}^{n}2^k=62", "\\sum_{k=1}^{n}k=56", "\\sum_{k=3}^{n}k=12", "\\sum_{k=1}^{n}k^2=385", "\\sum_{k=-3}^{3}k^2",
            "\\sum_{r=1}^{n}(3r^2+2r+1)", "\\sum_{k=1}^{n}(2^k+k)", "\\sum_{k=1}^{n}(-2)^k", "\\sum_{k=1}^{n}3\\cdot(\\frac{1}{3})^k", "\\sum_{k=1}^{n}k=\\frac{15}{8}",
        )) LatexSanity.assertParses(solved(raw), raw)
    }

    @Test fun mixedGeometricAndPolynomial() {
        assertEquals("(3^(n+1)+2n²+2n-1)/2", plain("\\sum_{k=0}^{n}(3^k+2k)"))
    }

    @Test fun aWrongClosedFormIsCaught() {
        try {
            // numeric bounds
            SumHooks.tamper = { it + Rational.ONE }
            for (raw in listOf("\\sum_{k=1}^{20}(3k+2)", "\\sum_{k=0}^{9}2^k", "\\sum_{k=1}^{500000}k")) {
                val r = SolvePipeline.run(raw, DotMeaning.Multiply)
                assertTrue("$raw: $r", r is SolveResult.Failed)
                assertEquals("Can't solve this sum reliably", (r as SolveResult.Failed).title)
            }
            // symbolic bounds, and an equation built on one
            for (raw in listOf("\\sum_{k=1}^{n}k^2", "\\sum_{k=0}^{n-1}3^k", "\\sum_{k=1}^{n}k=55")) {
                val r = SolvePipeline.run(raw, DotMeaning.Multiply)
                assertTrue("$raw: $r", r is SolveResult.Failed && r.title == "Can't solve this sum reliably")
            }
            // a value that is wrong only for some n is caught as well
            SumHooks.tamper = { if (it.den == BigInteger.ONE && it.num == BigInteger.TEN) it + Rational.ONE else it }
            val r = SolvePipeline.run("\\sum_{k=1}^{n}k", DotMeaning.Multiply)
            assertTrue("$r", r is SolveResult.Failed && r.title == "Can't solve this sum reliably")
        } finally {
            SumHooks.tamper = null
        }
        assertTrue(SolvePipeline.run("\\sum_{k=1}^{20}(3k+2)", DotMeaning.Multiply) is SolveResult.Solved)
    }
}
