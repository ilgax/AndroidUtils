package dev.ilgax.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogInequalityTest {
    // the six cases of the brief
    @Test fun baseTwo() = assertEquals("x > 9", answerOf("\\log_2(x-1)>3"))
    @Test fun naturalLog() = assertEquals("x ∈ (0, e]", answerOf("\\ln x\\le 1"))
    @Test fun baseBelowOneFlips() {
        val r = solved("\\log_{1/2}(x)>2")
        assertEquals("x ∈ (0, 1/4)", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.contains("below 1") && it.contains("flips") })
    }
    @Test fun squareInTheArgument() = assertEquals("x ∈ (-2, -1) ∪ (1, 2)", answerOf("\\log_3(x^2-1)<1"))
    @Test fun sumOfLogs() {
        val r = solved("\\log x+\\log(x-3)\\le1")
        assertEquals("x ∈ (3, 5]", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Product rule") })
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Domain") })
    }
    @Test fun equalLogsDifferentArguments() = assertEquals("x ∈ (3/2, 4]", answerOf("\\ln(x+1)\\ge\\ln(2x-3)"))

    // boundaries: strict and non-strict, and endpoints outside the domain
    @Test fun nonStrictIncludesTheEndpoint() = assertEquals("x ≥ 9", answerOf("\\log_2(x-1)\\ge3"))
    @Test fun strictExcludesTheEndpoint() = assertEquals("x ∈ (1, 9)", answerOf("\\log_2(x-1)<3"))
    @Test fun exactEndpointIsKept() {
        assertEquals("x ≥ e", answerOf("\\ln x\\ge1"))
        assertEquals("x ∈ (0, e)", answerOf("\\ln x<1"))
    }
    @Test fun domainEndpointIsNeverIncluded() = assertEquals("x ∈ (0, e²)", answerOf("\\ln\\sqrt{x}<1"))
    @Test fun negativeCoefficientFlips() = assertEquals("x ∈ (0, 1/2)", answerOf("-2\\log_2(x)>2"))
    @Test fun baseBelowOneNonStrict() = assertEquals("x ≥ 5", answerOf("\\log_{1/2}(x-1)\\le-2"))
    @Test fun fractionalBaseBelowOne() = assertEquals("x ∈ (0, 2/3)", answerOf("\\log_{2/3}x>1"))
    @Test fun twoLogsWithAnIrrationalEndpoint() = assertEquals("x ≥ 1 + √2", answerOf("\\ln x+\\ln(x-2)\\ge0"))
    @Test fun powerRuleAcrossLogs() = assertEquals("x ∈ (2, 4]", answerOf("\\ln x\\ge2\\ln(x-2)"))
    @Test fun differentBasesThatShareABase() = assertEquals("x ∈ (1, 3)", answerOf("\\log_2(x-1)+\\log_2(x+1)<3"))
    @Test fun negativeExponentEndpoint() = assertEquals("x > 1/e⁵", answerOf("\\ln x>-5"))
    @Test fun squareLogDropsTheOrigin() = assertEquals("x ∈ (-e², 0) ∪ (0, e²)", answerOf("\\ln(x^2)<4"))

    // empty and full results
    @Test fun emptyDomain() {
        val r = solved("\\ln x>\\ln(-x-1)")
        assertEquals("No solution", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().contains("The domain is empty"))
    }
    @Test fun nothingInTheDomainSatisfiesIt() {
        assertEquals("No solution", answerOf("\\ln(x-5)>\\ln(x+7)"))
        assertEquals("No solution", answerOf("\\log_2(x)\\le\\log_2(x-3)"))
    }
    @Test fun everyXInTheDomain() {
        val r = solved("\\ln x>\\ln(x-1)")
        assertEquals("x > 1", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().contains("Every x in the domain works"))
        assertEquals("x ∈ ℝ", answerOf("\\log_2(x^2+1)\\ge0"))
        assertEquals("x ∈ (-∞, 0) ∪ (0, ∞)", answerOf("\\log_2(x^2+1)>0"))
    }
    @Test fun identicalSidesFollowTheStrictness() {
        assertEquals("x > 0", answerOf("\\ln x\\le\\ln x"))
        assertEquals("No solution", answerOf("\\ln x<\\ln x"))
    }

    // other shapes
    @Test fun logOfAnExponential() = assertEquals("x > 3", answerOf("\\log_2(4^x)>6"))
    @Test fun numericBoundaryPoints() {
        val r = solved("\\ln x<x-2")
        assertEquals("x ∈ (0, 0.1586) ∪ (3.1462, ∞)", r.answers[0].plain)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Some endpoints are approximate") })
    }
    @Test fun unrelatedBasesUseTheEquationSolver() {
        val r = solved("\\log_2 x>\\log_3 x")
        assertEquals("x > 1", r.answers[0].plain)
        assertTrue(r.captions().toString(), r.captions().any { it.startsWith("Boundary points") })
    }
    @Test fun irrationalValueOfTheLog() = assertEquals("x > 2.6651", answerOf("\\log_2 x>\\sqrt2"))
    @Test fun constantsAreCompared() {
        assertEquals("True", answerOf("\\log_2 5>\\log_3 7"))
        assertEquals("True", answerOf("\\log_2 8>2"))
    }

    // what is rejected
    @Test fun invalidBasesAreExplained() {
        assertTrue(failedReason("\\log_1 x>2").contains("positive and not 1"))
        assertTrue(failedReason("\\log_{-2}x>1").contains("positive and not 1"))
        assertTrue(failedReason("\\log_1 x=2").contains("positive and not 1"))
        assertTrue(failedReason("\\log_{-2}x=2").contains("positive and not 1"))
    }
    @Test fun unknownBaseInAnInequalityIsUnsupported() = assertTrue(failedReason("\\log_x 8>3").contains("unknown base"))
    @Test fun chainsWithLogsAreUnsupported() = assertTrue(failedReason("1<\\ln x<2").contains("chain"))

    // the LaTeX of every step and answer is well formed (no KaTeX needed)
    @Test fun outputIsWellFormedLatex() {
        for (q in listOf(
            "\\log_2(x-1)>3", "\\ln x\\le 1", "\\ln x\\ge1", "\\log_{1/2}(x)>2", "\\log_3(x^2-1)<1", "\\log x+\\log(x-3)\\le1",
            "\\ln(x+1)\\ge\\ln(2x-3)", "\\ln x\\ge2\\ln(x-2)", "\\ln x<x-2", "\\ln x>\\ln(-x-1)", "\\ln x>\\ln(x-1)",
        )) LatexSanity.assertParses(solved(q), q)
    }
}
