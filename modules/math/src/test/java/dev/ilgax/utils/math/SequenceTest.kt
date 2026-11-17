package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.Rational
import dev.ilgax.utils.math.solver.SeqHooks
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arithmetic and geometric sequences, from explicit formulas, known terms, d, r / q, with queries. */
class SequenceTest {
    private val nl = "\\\\"

    @Test fun arithmeticExplicitFormula() {
        assertEquals("a_10 = 39", answerOf("a_n=3+4(n-1)$nl a_{10}"))
        assertEquals("S_10 = 210", answerOf("a_n=3+4(n-1)$nl S_{10}"))
        assertEquals("S_10 = 210", answerOf("a_n=3+4(n-1)$nl \\sum_{k=1}^{10}a_k"))
        assertEquals("S_10 = 210", answerOf("a_n=3+4(n-1), S_{10}"))
        assertEquals("a_10 = 39 ; S_10 = 210", answerOf("a_n=3+4(n-1), a_{10}, S_{10}"))
        assertEquals("d = 4 ; a_1 = 3 ; a_n = 4n-1", answerOf("a_n=3+4(n-1)"))
        assertEquals("a_10 = 28", answerOf("a_n=3n-2\\\\a_{10}"))
        assertEquals("a_4 = 5", answerOf("a_{n}=\\frac{n+6}{2}$nl a_{4}"))
    }

    @Test fun arithmeticFromTwoTerms() {
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2", answerOf("a_3=7, a_8=22"))
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2", answerOf("a_3=7$nl a_8=22"))
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2 ; a_20 = 58", answerOf("a_3=7, a_8=22, a_{20}"))
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2 ; S_20 = 590", answerOf("a_3=7, a_8=22, S_{20}"))
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2 ; a_20 = 58 ; S_20 = 590", answerOf("a_3=7$nl a_8=22$nl a_{20}$nl S_{20}"))
        val r = solved("a_3=7, a_8=22")
        // only one reading: 22/7 has no rational 5th root
        assertEquals(3, r.answers.size)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Arithmetic sequence: a_n = a_1 + (n−1)d") })
        assertTrue(r.notes.toString(), r.notes.any { it.contains("No geometric reading") })
        // non-integer difference
        assertEquals("d = 1/2 ; a_1 = 1 ; a_n = (n+1)/2", answerOf("a_1=1, a_3=2, arithmetic"))
    }

    @Test fun arithmeticFindN() {
        assertEquals("n = 16", answerOf("a_1=5, d=3, a_n=50"))
        assertEquals("n = 16", answerOf("a_1=5$nl d=3$nl a_n=50"))
        assertEquals("No natural number n gives a_n = 51", answerOf("a_1=5, d=3, a_n=51"))
        assertEquals("No natural number n gives a_n = -4", answerOf("a_1=5, d=3, a_n=-4"))
        assertEquals("n = 1", answerOf("a_1=5, d=3, a_n=5"))
        assertEquals("n = 21", answerOf("a_1=100, d=-5, a_n=0"))
        val r = solved("a_1=5, d=3, a_n=51")
        assertTrue(r.steps.toString(), r.steps.any { it.caption.contains("not a natural number") })
    }

    @Test fun arithmeticSums() {
        assertEquals("S_10 = 185", answerOf("a_1=5, d=3, S_{10}"))
        assertEquals("S_n = n(3n+7)/2", answerOf("a_1=5, d=3, S_n"))
        assertEquals("S_n = n²", answerOf("x_1=1, d=2, S_n"))
        assertEquals("S_1 = 5", answerOf("a_1=5, d=3, S_1"))
        assertEquals("Σ a_k (k=3..6) = 62", answerOf("a_1=5, d=3, \\sum_{k=3}^{6}a_k"))
    }

    @Test fun geometricBasics() {
        assertEquals("a_6 = 486", answerOf("a_1=2, r=3, a_6"))
        assertEquals("S_5 = 242", answerOf("a_1=2, r=3, S_5"))
        assertEquals("a_6 = 486", answerOf("a_1=2, q=3, a_6"))
        assertEquals("S_5 = 242", answerOf("a_1=2, q=3, S_{5}"))
        assertEquals("a_4 = 1/4 ; S_4 = 15/4", answerOf("a_1=2, r=\\frac{1}{2}, a_4, S_4"))
        assertEquals("a_5 = 16", answerOf("a_1=1, r=-2, a_5"))
        assertEquals("S_n = 3^n-1", answerOf("a_1=2, r=3, S_n"))
        assertEquals("S_n = 2^n-1", answerOf("a_1=1, r=2, S_n"))
        assertEquals("b_5 = 162", answerOf("b_n=2\\cdot3^{n-1}$nl b_{5}"))
        assertEquals("b_5 = 162", answerOf("b_n=2\\cdot 3^{n-1}, b_5"))
        assertEquals("u_4 = 24", answerOf("u_n=3\\cdot2^{n-1}, u_4"))
    }

    @Test fun geometricFromTwoTerms() {
        val r = solved("a_2=6, a_5=162")
        // both readings: arithmetic (d = 52) and geometric (r = 3, a_1 = 2)
        val lines = r.answers.map { it.plain }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Arithmetic: d = 52, a_1 = -46"))
        assertEquals("Geometric: r = 3, a_1 = 2, a_n = 2·3^(n-1)", lines[1])
        assertTrue(r.notes.toString(), r.notes.any { it.contains("both") })
        assertEquals("r = 3 ; a_1 = 2 ; a_n = 2·3^(n-1)", answerOf("a_2=6, a_5=162, geometric"))
        assertEquals("r = 3 ; a_1 = 2 ; a_n = 2·3^(n-1) ; a_6 = 486", answerOf("a_2=6$nl a_5=162$nl a_6$nl \\text{geometric}"))
        assertEquals("r = 3 ; a_1 = 2 ; a_n = 2·3^(n-1) ; a_6 = 486", answerOf("a_2=6$nl a_5=162$nl a_6$nl \\text{geometrik}"))
        // negative ratio with an odd gap
        assertEquals("r = -2 ; a_1 = 1 ; a_n = (-2)^(n-1)", answerOf("a_2=-2, a_5=16, geometric"))
    }

    @Test fun evenGapGivesBothSigns() {
        val r = solved("a_1=2, a_3=18")
        val lines = r.answers.map { it.plain }
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Arithmetic: d = 8"))
        assertEquals("Geometric, r = 3: r = 3, a_1 = 2, a_n = 2·3^(n-1)", lines[1])
        assertEquals("Geometric, r = -3: r = -3, a_1 = 2, a_n = 2·(-3)^(n-1)", lines[2])
        val g = solved("a_1=2, a_3=18, geometric, a_4")
        assertEquals(listOf("Geometric, r = 3: r = 3, a_1 = 2, a_n = 2·3^(n-1), a_4 = 54", "Geometric, r = -3: r = -3, a_1 = 2, a_n = 2·(-3)^(n-1), a_4 = -54"), g.answers.map { it.plain })
        // a_2 = 6, a_4 = 54 as well
        assertEquals(2, solved("a_2=6, a_4=54, geometric").answers.size)
    }

    @Test fun geometricFindN() {
        assertEquals("n = 6", answerOf("a_1=3, r=2, a_n=96"))
        assertEquals("n = 6", answerOf("a_1=3, q=2, a_n=96"))
        assertEquals("n = 4", answerOf("a_1=1, r=\\frac{1}{3}, a_n=\\frac{1}{27}"))
        assertEquals("n = 4", answerOf("a_1=2, r=-2, a_n=-16"))
        assertEquals("No natural number n gives a_n = 100", answerOf("a_1=3, r=2, a_n=100"))
        assertEquals("No natural number n gives a_n = -96", answerOf("a_1=3, r=2, a_n=-96"))
        val r = solved("a_1=3, r=2, a_n=96")
        assertTrue(r.steps.any { it.latex.contains("\\log_{2}32=5") })
        val nr = solved("a_1=3, r=2, a_n=100")
        assertTrue(nr.steps.toString(), nr.steps.any { it.latex.contains("\\log_{2}") && it.latex.contains("\\approx 6.059") })
    }

    @Test fun degenerateSequences() {
        // d = 0
        assertEquals("u_10 = 4", answerOf("u_1=4, d=0, u_{10}"))
        assertEquals("S_10 = 40", answerOf("a_1=4, d=0, S_{10}"))
        assertEquals("n can be any natural number", answerOf("a_1=4, d=0, a_n=4"))
        assertEquals("No natural number n gives a_n = 5", answerOf("a_1=4, d=0, a_n=5"))
        // r = 1
        assertEquals("a_7 = 3", answerOf("a_1=3, r=1, a_7"))
        assertEquals("S_5 = 15", answerOf("a_1=3, r=1, S_{5}"))
        assertEquals("S_n = 3n", answerOf("a_1=3, r=1, S_n"))
        assertEquals("n can be any natural number", answerOf("a_1=3, r=1, a_n=3"))
        assertEquals("No natural number n gives a_n = 4", answerOf("a_1=3, r=1, a_n=4"))
        // r = -1
        assertEquals("a_5 = 3", answerOf("a_1=3, r=-1, a_5"))
        assertEquals("S_5 = 3", answerOf("a_1=3, r=-1, S_5"))
        // a negative first term and a zero term
        assertEquals("a_4 = -9", answerOf("a_1=-3, d=-2, a_4"))
        assertTrue(failedReason("a_1=3, r=0, a_4").contains("isn't allowed"))
    }

    @Test fun readingsAndTheirWords() {
        // two terms alone fit both
        val both = solved("a_1=2, a_3=18, a_5")
        assertEquals(3, both.answers.size)
        // words choose
        assertEquals(4, solved("a_1=2, a_3=18, \\text{arithmetic}, a_5").answers.size)
        assertEquals("d = 8 ; a_1 = 2 ; a_n = 8n-6 ; a_5 = 34", answerOf("a_1=2, a_3=18, \\text{aritmetik}, a_5"))
        assertEquals(2, solved("a_1=2, a_3=18, \\text{geometrik}, a_5").answers.size)
        // d chooses arithmetic, r and q choose geometric
        assertEquals(1, solved("a_1=2, d=8, a_5").answers.size)
        assertEquals(1, solved("a_1=2, q=3, a_5").answers.size)
        // a formula linear in n is arithmetic, c·r^n geometric
        assertTrue(solved("a_n=3n-2, a_5").captions().first().startsWith("Arithmetic"))
        assertTrue(solved("a_n=2\\cdot 3^{n}, a_5").captions().first().startsWith("Geometric"))
        assertEquals("a_5 = 486", answerOf("a_n=2\\cdot 3^{n}, a_5"))
        assertTrue(failedReason("a_n=3n-2, geometric").contains("linear"))
        assertTrue(failedReason("a_1=2, r=3, arithmetic").contains("ratio"))
        assertTrue(failedReason("a_1=2, d=3, geometric, a_4").contains("difference"))
        assertTrue(failedReason("a_1=2, d=3, r=3, a_4").contains("both d"))
        // the ratio of two terms is not rational: no geometric reading, arithmetic only
        val one = solved("a_1=1, a_3=2")
        assertEquals(3, one.answers.size)
        assertTrue(failedReason("a_1=1, a_3=2, geometric").contains("rational"))
        assertTrue(failedReason("a_1=1, a_3=-2, geometric").contains("negative"))
    }

    @Test fun notationVariants() {
        assertEquals("a_10 = 39", answerOf("a_{n}=3+4(n-1)\\\\a_{10}"))
        assertEquals("a_10 = 39", answerOf("a_{n}=3+4(n-1)\\\\a_{10}=?"))
        assertEquals("a_10 = 39", answerOf("a_{n}=3+4(n-1)\\\\a_{10}="))
        assertEquals("a_10 = 39", answerOf("a_n = 3 + 4 ( n - 1 ) , a_{10}"))
        assertEquals("u_10 = 39", answerOf("u_n=3+4(n-1)\\\\u_{10}"))
        assertEquals("a_6 = 486", answerOf("a_{1}=2,r=3,a_{6}"))
        assertEquals("a_10 = 39", answerOf("a_n=3+4\\cdot (n-1)\\\\a_{10}"))
        assertEquals("a_3 = 14", answerOf("a_1=2, d=6, a_3"))
        assertEquals("S_10 = 210", answerOf("a_k=3+4(k-1), S_{10}"))
        // x_n is a subscripted x, while \times is the variable x
        assertEquals("x_5 = 11", answerOf("x_n=2n+1\\\\x_{5}"))
        // a two-digit index without braces
        assertEquals("a_10 = 39", answerOf("a_n=3+4(n-1)\\\\a_10"))
    }

    @Test fun rejectedInputs() {
        assertTrue(failedReason("a_1=5, d=3, a_{2.5}").contains("natural number"))
        assertTrue(failedReason("a_1=5, d=3, a_{0}").contains("below 1"))
        assertTrue(failedReason("a_1=5, d=3, a_{n+1}").contains("recurrence"))
        assertTrue(failedReason("a_1=5").contains("two terms"))
        assertTrue(failedReason("a_3=7, a_3=8").contains("twice"))
        assertTrue(failedReason("a_1=1, a_2=2, a_3=4, arithmetic").contains("don't all fit"))
        assertTrue(failedReason("a_{n+1}=a_n+3, a_1=2").contains("recurrence"))
        assertTrue(failedReason("S_{10}=100, a_1=2").contains("isn't supported"))
    }

    @Test fun checksOfGivenTerms() {
        // three terms that agree
        assertEquals("d = 3 ; a_1 = 1 ; a_n = 3n-2", answerOf("a_3=7, a_8=22, a_5=13"))
        // three terms that do not fit one sequence
        assertTrue(failedReason("a_3=7, a_8=22, a_5=14").contains("don't all fit"))
    }

    @Test fun everyOutputIsWellFormedLatex() {
        for (raw in listOf(
            "a_n=3+4(n-1)\\\\a_{10}", "a_n=3+4(n-1)\\\\S_{10}", "a_3=7, a_8=22", "a_3=7, a_8=22, a_{20}, S_{20}", "a_1=5, d=3, a_n=50", "a_1=2, r=3, a_6", "a_1=2, r=3, S_5",
            "a_2=6, a_5=162", "a_1=2, a_3=18", "a_1=3, r=2, a_n=96", "a_1=3, q=2, a_n=96", "a_1=5, d=3, a_n=51", "a_1=3, r=2, a_n=100", "a_1=5, d=3, S_n", "a_1=2, r=3, S_n",
            "a_1=4, d=0, a_n=4", "a_1=3, r=1, S_n", "a_1=2, r=\\frac{1}{2}, a_4, S_4", "a_1=1, r=-2, a_5", "a_1=5, d=3, \\sum_{k=3}^{6}a_k", "a_1=2, r=-3, S_n",
        )) LatexSanity.assertParses(solved(raw), raw)
    }

    @Test fun aWrongAnswerIsCaught() {
        try {
            SeqHooks.tamper = { it + Rational.ONE }
            for (raw in listOf("a_n=3+4(n-1)\\\\a_{10}", "a_1=2, r=3, a_6", "a_1=2, r=3, S_5", "a_1=5, d=3, S_{10}", "a_3=7, a_8=22", "a_1=5, d=3, a_n=50", "a_1=3, r=2, a_n=96", "a_1=5, d=3, S_n")) {
                val r = SolvePipeline.run(raw, DotMeaning.Multiply)
                assertTrue("$raw: $r", r is SolveResult.Failed)
                assertEquals("Can't solve this sequence reliably", (r as SolveResult.Failed).title)
            }
        } finally {
            SeqHooks.tamper = null
        }
        assertTrue(SolvePipeline.run("a_1=2, r=3, a_6", DotMeaning.Multiply) is SolveResult.Solved)
        assertFalse(SolvePipeline.run("a_1=2, r=3, a_6", DotMeaning.Multiply) is SolveResult.Failed)
    }

    @Test fun otherMathIsUnaffected() {
        assertEquals("x = 3", answerOf("2x=6"))
        assertEquals("x = 2", answerOf("\\log_2 x=1"))
        assertEquals("y = 2x - 1", answerOf("f(x)=x^2 \\\\ x_0=1"))
    }
}
