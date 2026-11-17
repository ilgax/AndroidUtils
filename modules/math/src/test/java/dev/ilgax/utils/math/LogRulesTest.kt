package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change of base, an unknown base, and logarithms of roots, exponentials and other logarithms. */
class LogRulesTest {
    // ---- change of base and coefficients
    @Test fun chainOfConstants() {
        val r = solved("\\log_2 3\\cdot\\log_3 8")
        assertEquals("3", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Change of base") })
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Cancel") })
    }
    @Test fun chainWithAFractionalResult() = assertEquals("3/2 = 1.5", answerOf("\\log_4 3\\cdot\\log_3 8"))
    @Test fun symbolicChain() {
        val r = solved("\\log_a b\\cdot\\log_b c")
        assertEquals("log_a c", r.answers[0].plain)
        assertEquals("\\log_a c", r.answers[0].latex)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Valid where every base is positive") })
    }
    @Test fun quotientOfSameBaseLogs() {
        val r = solved("\\frac{\\log 8}{\\log 2}")
        assertEquals("3", r.answers[0].plain)
        assertTrue(r.steps.toString(), r.steps.any { it.latex == "\\log_2 8" })
    }
    @Test fun coefficientBecomesARoot() {
        assertEquals("log_2 3 ≈ 1.585", answerOf("\\frac{1}{2}\\log_2 9"))
        assertEquals("-log_3 2 ≈ -0.631", answerOf("-\\frac{1}{2}\\log_3 4"))
        assertEquals("log_2 3 ≈ 1.585", answerOf("\\frac{1}{3}\\log_2 27"))
    }
    @Test fun coefficientThatDoesNotImproveStays() = assertEquals("2log_2 3 ≈ 3.17", answerOf("2\\log_2 3"))
    @Test fun exactBasesAndArguments() {
        assertEquals("6", answerOf("\\log_{\\sqrt2}8"))
        assertEquals("1/3 ≈ 0.33", answerOf("\\log_8 2"))
        assertEquals("3/2 = 1.5", answerOf("\\log_2\\sqrt{8}"))
    }
    @Test fun baseEIsNaturalLog() {
        assertEquals(answerOf("\\ln x=2"), answerOf("\\log_{e}x=2"))
        assertEquals("1", answerOf("\\log_e e"))
    }
    @Test fun lnWithoutBaseAndLg() {
        assertEquals("x = 100", answerOf("\\log x=2"))
        assertEquals("x = 100", answerOf("\\lg x=2"))
    }
    @Test fun changeOfBaseInAnEquation() {
        assertEquals("x = 9", answerOf("\\log_4 x=\\log_2 3"))
        assertEquals("x = 8", answerOf("\\frac{\\ln x}{\\ln 2}=3"))
    }
    @Test fun chainWithTheUnknownCancelsInAnEquation() {
        val r = solved("\\log_2 x\\cdot\\log_x 8=3")
        assertEquals("x ∈ (0, 1) ∪ (1, ∞)", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Domain") })
        assertEquals("No solution", answerOf("\\log_2 x\\cdot\\log_x 8=2"))
    }
    @Test fun chainWithTheUnknownNextToOtherTermsIsUnsupported() =
        assertTrue(failedReason("\\log_2 x\\cdot\\log_x 8+x=4").contains("cancels"))
    @Test fun unrelatedLogsStillNeedProducts() = assertTrue(failedReason("\\log_2 3\\cdot\\log_5 7").contains("products or powers of logarithms"))

    // ---- unknown base
    @Test fun unknownBaseCube() = assertEquals("x = 2", answerOf("\\log_x 8=3"))
    @Test fun unknownBaseRejectsNegative() {
        val r = solved("\\log_x 16=2")
        assertEquals("x = 4", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Check x = -4") && it.contains("rejected") })
    }
    @Test fun unknownBaseNegativeExponent() = assertEquals("x = 1/2 = 0.5", answerOf("\\log_x 4=-2"))
    @Test fun unknownBaseWithAnUnknownArgument() {
        val r = solved("\\log_x(2x+3)=2")
        assertEquals("x = 3", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Rejected x = -1") })
    }
    @Test fun unknownBaseIdentity() {
        val r = solved("\\log_x 1=0")
        assertEquals("x ∈ (0, 1) ∪ (1, ∞)", r.answers[0].plain)
        assertTrue(r.steps.toString(), r.steps.any { it.latex == "x>0,\\ x\\ne1" })
    }
    @Test fun unknownBaseFractionalValue() = assertEquals("x = 81", answerOf("\\log_x 9=1/2"))
    @Test fun unknownBaseOfOneIsNeverOne() {
        assertEquals("No solution", answerOf("\\log_x 1=1"))
        assertEquals("No solution", answerOf("\\log_x 8=0"))
    }
    @Test fun unknownBaseShiftedIdentity() = assertEquals("x ∈ (-1, 0) ∪ (0, ∞)", answerOf("\\log_{x+1}1=0"))
    @Test fun unknownBaseWithOtherLogsIsUnsupported() {
        assertTrue(failedReason("\\log_x 8+\\log_2 x=4").contains("unknown base"))
        assertTrue(failedReason("\\log_x(2x+3)=\\ln x").contains("unknown base"))
    }
    @Test fun unknownBaseOutputIsWellFormed() {
        for (q in listOf("\\log_x 8=3", "\\log_x 16=2", "\\log_x 4=-2", "\\log_x(2x+3)=2", "\\log_x 1=0", "\\log_x 9=1/2", "\\log_2 x\\cdot\\log_x 8=3"))
            LatexSanity.assertParses(solved(q), q)
    }

    // ---- logs of roots, exponentials and other logs
    @Test fun lnOfARoot() {
        val r = solved("\\ln\\sqrt{x}=2")
        assertEquals("x = e⁴ ≈ 54.598", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Root") })
    }
    @Test fun logOfAPower() {
        val r = solved("\\log_2(4^x)=6")
        assertEquals("x = 3", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Power rule") })
    }
    @Test fun logOfAProductOfPowers() = assertEquals("x = 2", answerOf("\\log_3(9^x\\cdot3)=5"))
    @Test fun lnOfExp() = assertEquals("x = 2", answerOf("\\ln(e^{2x})=4"))
    @Test fun logOfAPowerWithAQuadraticExponent() = assertEquals("x = ±2", answerOf("\\ln(e^{x^2})=4"))
    @Test fun lnOfLn() {
        val r = solved("\\ln(\\ln x)=0")
        assertEquals("x = e ≈ 2.718", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Undo the outer logarithm") })
    }
    @Test fun logOfLog() = assertEquals("x = 9", answerOf("\\log_2(\\log_3 x)=1"))
    @Test fun threeNestedLogs() = assertEquals("x = 100", answerOf("\\log_3(\\log_2(\\log x))=0"))
    @Test fun logOfASumWithAnExponential() = assertEquals("x = log_2 7 ≈ 2.807", answerOf("\\log_2(2^x+1)=3"))
    @Test fun logOfASumWithAnExponentialHasNoSolution() = assertEquals("No solution", answerOf("\\log_2(2^x+1)=0"))
    @Test fun logOfARootOfAPolynomial() = assertEquals("x = 3", answerOf("\\log_2\\sqrt{x+1}=1"))
    @Test fun logOfASumWithARoot() = assertEquals("x = 49", answerOf("\\log_2(\\sqrt{x}+1)=3"))
    @Test fun logOfAPowerOfAnIrrationalBase() = assertEquals("x ≈ 3.1546", answerOf("\\log_2(3^x)=5"))
    @Test fun nestedLogWithANonIntegerValueIsUnsupported() = assertTrue(failedReason("\\ln(\\ln x)=\\ln 2").contains("whole number"))
    @Test fun rootOfXNeedsPositiveInside() = assertEquals("No solution", answerOf("\\ln\\sqrt{x}=\\ln(-x-1)"))
    @Test fun timesIsTheVariable() = assertEquals("x = 8", answerOf("\\log_2\\times=3"))
    @Test fun newOutputIsWellFormed() {
        for (q in listOf(
            "\\ln\\sqrt{x}=2", "\\log_2(4^x)=6", "\\log_3(9^x\\cdot3)=5", "\\ln(e^{2x})=4", "\\ln(\\ln x)=0", "\\log_2(\\log_3 x)=1",
            "\\log_2(2^x+1)=3", "\\log_2\\sqrt{x+1}=1", "\\log_a b\\cdot\\log_b c", "\\frac{\\log 8}{\\log 2}", "\\log_2 3\\cdot\\log_3 8",
        )) LatexSanity.assertParses(solved(q), q)
    }
}
