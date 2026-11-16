package dev.ilgax.utils.math

import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.LatexNormalizer
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.solver.LFin
import dev.ilgax.utils.math.solver.LInf
import dev.ilgax.utils.math.solver.LV
import dev.ilgax.utils.math.solver.LimitHooks
import dev.ilgax.utils.math.solver.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limits: notation variants, the methods (as named steps), one-sided limits, honesty about what is unsupported, and the numeric safety check. */
class LimitTest {
    private fun plain(raw: String) = answerOf(raw)
    private fun tex(raw: String) = latexOf(raw)
    private fun caps(raw: String) = solved(raw).captions().joinToString(" | ")

    // ------------------------------------------------------------------ the cases from the brief

    @Test fun directSubstitution() {
        assertEquals("lim x→2 (x²+1) = 5", plain("\\lim_{x\\to2}(x^2+1)"))
        assertTrue(caps("\\lim_{x\\to2}(x^2+1)").contains("Direct substitution"))
    }

    @Test fun factorAndCancel() {
        assertTrue(tex("\\lim_{x\\to2}\\frac{x^2-4}{x-2}").endsWith("=4"))
        assertTrue(tex("\\lim_{x\\to1}\\frac{x^3-1}{x-1}").endsWith("=3"))
        assertTrue(tex("\\lim_{x\\to3}\\frac{x^2-9}{x^2-5x+6}").endsWith("=6"))
        val c = caps("\\lim_{x\\to2}\\frac{x^2-4}{x-2}")
        assertTrue(c, c.contains("0/0") && c.contains("factor (x-2)") && c.contains("Cancel"))
    }

    @Test fun atInfinityRational() {
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{3x^2+1}{x^2-5}").endsWith("=3"))
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{2x+1}{x^2}").endsWith("=0"))
        assertTrue(tex("\\lim_{x\\to-\\infty}\\frac{x^3}{x^2+1}").endsWith("=-\\infty"))
        assertTrue(caps("\\lim_{x\\to\\infty}\\frac{3x^2+1}{x^2-5}").contains("divide numerator and denominator by x^{2}"))
    }

    @Test fun atInfinityWithRoots() {
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{\\sqrt{x^2+1}}{x}").endsWith("=1"))
        assertTrue(tex("\\lim_{x\\to-\\infty}\\frac{\\sqrt{x^2+1}}{x}").endsWith("=-1"))
    }

    @Test fun conjugate() {
        assertTrue(tex("\\lim_{x\\to0}\\frac{\\sqrt{x+4}-2}{x}").contains("=\\frac{1}{4}"))
        assertTrue(caps("\\lim_{x\\to0}\\frac{\\sqrt{x+4}-2}{x}").contains("Conjugate"))
        // ∞ − ∞ with one root
        assertTrue(tex("\\lim_{x\\to\\infty}(\\sqrt{x^2+x}-x)").contains("=\\frac{1}{2}"))
        assertTrue(caps("\\lim_{x\\to\\infty}(\\sqrt{x^2+x}-x)").contains("Conjugate"))
    }

    @Test fun standardLimits() {
        assertTrue(tex("\\lim_{x\\to0}\\frac{\\sin 3x}{x}").endsWith("=3"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{1-\\cos x}{x^2}").contains("=\\frac{1}{2}"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{e^{x}-1}{x}").endsWith("=1"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{\\tan x}{x}").endsWith("=1"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{\\ln(1+x)}{x}").endsWith("=1"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{\\sin 5x}{\\sin 3x}").contains("=\\frac{5}{3}"))
        assertTrue(plain("\\lim_{x\\to\\infty}(1+\\frac{2}{x})^{x}").startsWith("lim x→∞ (1 + 2/x)^x = e² ≈ 7.389"))
        assertTrue(tex("\\lim_{x\\to\\infty}(1+\\frac{1}{x})^{x}").contains("=e\\approx2.718"))
        assertTrue(tex("\\lim_{x\\to0}(1+x)^{1/x}").contains("=e\\approx2.718"))
        val s = solved("\\lim_{x\\to0}\\frac{\\sin 3x}{x}")
        assertTrue(s.captions().any { it.startsWith("Standard limit") && it.contains("radians") })
        assertTrue(s.notes.any { it.contains("radians") })
    }

    @Test fun lhopital() {
        val s = solved("\\lim_{x\\to0}\\frac{x-\\sin x}{x^3}")
        assertTrue(s.answers[0].latex.contains("=\\frac{1}{6}"))
        assertEquals(3, s.captions().count { it.startsWith("L'Hôpital: 0/0 form") })
        assertTrue(s.captions().any { it.startsWith("Differentiate numerator and denominator separately") })
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{\\ln x}{x}").endsWith("=0"))
        assertEquals(1, solved("\\lim_{x\\to\\infty}\\frac{\\ln x}{x}").captions().count { it.startsWith("L'Hôpital") })
        val e = solved("\\lim_{x\\to\\infty}\\frac{x^2}{e^{x}}")
        assertTrue(e.answers[0].latex.endsWith("=0"))
        assertEquals(2, e.captions().count { it.startsWith("L'Hôpital: ∞/∞ form") })
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{e^x}{x^2}").endsWith("=\\infty"))
    }

    @Test fun polesAndSides() {
        assertTrue(tex("\\lim_{x\\to1^{+}}\\frac{1}{x-1}").endsWith("=\\infty"))
        assertTrue(tex("\\lim_{x\\to1^{-}}\\frac{1}{x-1}").endsWith("=-\\infty"))
        assertTrue(tex("\\lim_{x\\to 1^+}\\frac{1}{x-1}").endsWith("=\\infty"))
        assertTrue(tex("\\lim_{x\\to1}\\frac{1}{x-1}").endsWith("\\text{Does not exist}"))
        assertTrue(plain("\\lim_{x\\to1}\\frac{1}{x-1}").contains("left: -∞, right: ∞"))
        val d = solved("\\lim_{x\\to0}\\frac{|x|}{x}")
        assertTrue(d.answers[0].latex.endsWith("\\text{Does not exist}"))
        assertTrue(d.answers[0].plain.contains("left: -1, right: 1"))
        assertTrue(d.captions().contains("Left-hand limit") && d.captions().contains("Right-hand limit"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{1}{x^2}").endsWith("=\\infty"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{1}{x}").endsWith("\\text{Does not exist}"))
        assertTrue(tex("\\lim_{x\\to\\frac{\\pi}{2}^{-}}\\tan x").endsWith("=\\infty"))
    }

    @Test fun zeroTimesInfinityAndPowers() {
        val s = solved("\\lim_{x\\to0^{+}}x\\ln x")
        assertTrue(s.answers[0].latex.endsWith("=0"))
        assertTrue(s.captions().any { it.startsWith("0·∞ form") } && s.captions().any { it.startsWith("L'Hôpital") })
        assertTrue(tex("\\lim_{x\\to\\infty}xe^{-x}").endsWith("=0"))
        assertTrue(tex("\\lim_{x\\to0^{+}}x^{x}").endsWith("=1"))
        assertTrue(tex("\\lim_{x\\to0}\\frac{1}{x}-\\frac{1}{x^2}").endsWith("=-\\infty"))
    }

    @Test fun oneSidedDomainAndBoundedOscillation() {
        val s = solved("\\lim_{x\\to0}\\sqrt{x}")
        assertTrue(s.answers[0].latex.endsWith("=0"))
        assertTrue(s.notes.any { it.contains("only defined to the right") })
        assertTrue(tex("\\lim_{x\\to0}\\sin\\frac{1}{x}").endsWith("\\text{Does not exist (oscillates)}"))
        assertTrue(tex("\\lim_{x\\to\\infty}\\sin x").endsWith("\\text{Does not exist (oscillates)}"))
        assertTrue(tex("\\lim_{x\\to0}x\\sin\\frac{1}{x}").endsWith("=0"))
        assertTrue(tex("\\lim_{x\\to\\infty}\\frac{\\sin x}{x}").endsWith("=0"))
    }

    @Test fun otherVariablesAndDefinitionOfDerivative() {
        assertTrue(tex("\\lim_{t\\to 3}\\frac{t^2-9}{t-3}").endsWith("=6"))
        assertTrue(tex("\\lim_{n\\to\\infty}\\frac{2n+1}{n+3}").endsWith("=2"))
        assertTrue(tex("\\lim_{h\\to0}\\frac{(x+h)^2-x^2}{h}").endsWith("=2x"))
        assertTrue(tex("\\lim_{h\\to0}\\frac{(x+h)^3-x^3}{h}").endsWith("=3x^{2}"))
    }

    // ------------------------------------------------------------------ notation the model may emit

    @Test fun notationVariants() {
        val want = "lim x→2 (x²-4)/(x - 2) = 4"
        for (v in listOf(
            "\\lim_{x\\to2}\\frac{x^2-4}{x-2}", "\\lim\\limits_{x \\to 2}\\frac{x^2-4}{x-2}", "\\lim_{x\\rightarrow2}\\frac{x^2-4}{x-2}",
            "\\lim_{x\\longrightarrow 2}\\frac{x^2-4}{x-2}", "\\lim_{x->2}\\frac{x^2-4}{x-2}", "\\operatorname{lim}_{x\\to 2}\\frac{x^2-4}{x-2}",
            "lim_{x\\to2}\\frac{x^2-4}{x-2}", "\\lim_x\\to 2 \\frac{x^2-4}{x-2}", "\\lim _ { x \\rightarrow 2 } \\frac { x ^ { 2 } - 4 } { x - 2 }",
            "\\lim_{x\\to2}\\frac{x^2-4}{x-2}=", "\\mathrm{lim}_{x→2}\\frac{x^2-4}{x-2}",
        )) assertEquals(v, want, plain(v))
        assertTrue(tex("\\lim_{x\\to+\\infty}\\frac{2x+1}{x^2}").endsWith("=0"))
        assertTrue(tex("lim_{x->0^+}\\frac{1}{x}").endsWith("=\\infty"))
        assertTrue(tex("\\lim_{x\\rightarrow 0^{-}}\\frac{1}{x}").endsWith("=-\\infty"))
        assertTrue(tex("\\lim_{x\\to0+}\\frac1x").endsWith("=\\infty"))
        assertTrue(LatexNormalizer.normalize("\\lim_{x \\to ∞}\\frac{1}{x}").latex.startsWith("\\lim_{x\\to\\infty}"))
    }

    // ------------------------------------------------------------------ honest about the unsupported

    @Test fun unsupported() {
        assertTrue(failedReason("\\lim_{(x,y)\\to(0,0)}\\frac{xy}{x^2+y^2}").contains("multivariable"))
        assertTrue(failedReason("\\lim_{n\\to\\infty}\\frac{n!}{2^n}").contains("factorials"))
        // needs five rounds of L'Hôpital or a series
        assertTrue(failedReason("\\lim_{x\\to0}\\frac{\\sin x-x}{x^5}").contains("don't reach"))
        assertTrue(failedReason("\\lim_{h\\to0}\\frac{f(x+h)-f(x)}{h}").contains("limit"))
        assertTrue(failedReason("\\lim_{x\\to0}\\frac{\\sin x}{x}=1").contains("stated answer"))
    }

    // ------------------------------------------------------------------ the safety check

    @Test fun aBrokenMethodIsCaught() {
        try {
            LimitHooks.tamper = { if (it is LFin) LFin(null, it.d + 1.0) else it }
            for (raw in listOf("\\lim_{x\\to2}\\frac{x^2-4}{x-2}", "\\lim_{x\\to0}\\frac{x-\\sin x}{x^3}", "\\lim_{x\\to\\infty}\\frac{3x^2+1}{x^2-5}")) {
                val r = SolvePipeline.run(raw, DotMeaning.Multiply)
                assertTrue("$raw: $r", r is SolveResult.Failed)
                assertEquals("Can't solve this limit reliably", (r as SolveResult.Failed).title)
            }
            LimitHooks.tamper = { if (it is LInf) LInf(-it.sign) else if (it is LFin) LInf(1) else it }
            for (raw in listOf("\\lim_{x\\to1^{+}}\\frac{1}{x-1}", "\\lim_{x\\to0}\\frac{1}{x^2}", "\\lim_{x\\to\\infty}\\frac{\\ln x}{x}")) {
                val r = SolvePipeline.run(raw, DotMeaning.Multiply)
                assertTrue("$raw: $r", r is SolveResult.Failed && r.title == "Can't solve this limit reliably")
            }
            // a wrong "does not exist": one side replaced
            LimitHooks.tamper = { if (it is LInf && it.sign > 0) LInf(-1) else it }
            assertTrue(SolvePipeline.run("\\lim_{x\\to0}\\frac{1}{x}", DotMeaning.Multiply) is SolveResult.Failed)
        } finally {
            LimitHooks.tamper = null
        }
        assertTrue(SolvePipeline.run("\\lim_{x\\to2}\\frac{x^2-4}{x-2}", DotMeaning.Multiply) is SolveResult.Solved)
    }

    // ------------------------------------------------------------------ a battery checked against numbers

    @Test fun battery() {
        val cases = mapOf(
            "\\lim_{x\\to1}\\frac{x^2-1}{x-1}" to "2", "\\lim_{x\\to-2}\\frac{x^2+3x+2}{x+2}" to "-1", "\\lim_{x\\to0}\\frac{x^2+x}{x}" to "1",
            "\\lim_{x\\to\\infty}\\frac{5x^3-x}{2x^3+7}" to "\\frac{5}{2}", "\\lim_{x\\to\\infty}\\frac{x^2+1}{x}" to "\\infty", "\\lim_{x\\to-\\infty}\\frac{x^2+1}{x}" to "-\\infty",
            "\\lim_{x\\to\\infty}\\frac{\\sqrt{4x^2+1}}{x}" to "2", "\\lim_{x\\to\\infty}\\frac{3x+1}{\\sqrt{x^2+4}}" to "3", "\\lim_{x\\to-\\infty}\\frac{3x+1}{\\sqrt{x^2+4}}" to "-3",
            "\\lim_{x\\to\\infty}\\frac{1}{x}" to "0", "\\lim_{x\\to\\infty}e^{-x}" to "0", "\\lim_{x\\to\\infty}e^{x}" to "\\infty", "\\lim_{x\\to\\infty}\\arctan x" to "\\frac{\\pi}{2}",
            "\\lim_{x\\to0^{+}}\\ln x" to "-\\infty", "\\lim_{x\\to1}\\frac{\\ln x}{x-1}" to "1", "\\lim_{x\\to0}\\frac{\\sin x}{2x}" to "\\frac{1}{2}",
            "\\lim_{x\\to0}\\frac{\\sin^2 x}{x^2}" to "1", "\\lim_{x\\to0}\\frac{1-\\cos 2x}{x^2}" to "2", "\\lim_{x\\to0}\\frac{e^{2x}-1}{x}" to "2",
            "\\lim_{x\\to1}\\frac{\\sqrt{x+3}-2}{x-1}" to "\\frac{1}{4}", "\\lim_{x\\to9}\\frac{\\sqrt{x}-3}{x-9}" to "\\frac{1}{6}", "\\lim_{x\\to0}\\frac{1-\\sqrt{1-x}}{x}" to "\\frac{1}{2}",
            "\\lim_{x\\to0}\\frac{e^x-1-x}{x^2}" to "\\frac{1}{2}", "\\lim_{x\\to0}\\frac{e^x-e^{-x}}{2x}" to "1", "\\lim_{x\\to\\infty}\\frac{x^3}{e^x}" to "0",
            "\\lim_{x\\to\\infty}\\frac{\\ln x}{\\sqrt{x}}" to "0", "\\lim_{x\\to\\infty}x^{2}e^{-x}" to "0", "\\lim_{x\\to0}\\frac{\\tan 2x}{\\sin 5x}" to "\\frac{2}{5}",
            "\\lim_{x\\to\\infty}(1+\\frac{3}{x})^{x}" to "e^{3}", "\\lim_{x\\to0}(1+2x)^{1/x}" to "e^{2}", "\\lim_{x\\to\\infty}(1-\\frac{1}{x})^{x}" to "\\frac{1}{e}",
            "\\lim_{x\\to0}\\cos x" to "1", "\\lim_{x\\to\\pi}\\sin x" to "0", "\\lim_{x\\to\\infty}(\\frac{2}{3})^{x}" to "0",
            "\\lim_{x\\to2}\\frac{1}{(x-2)^2}" to "\\infty", "\\lim_{x\\to2}\\frac{-1}{(x-2)^2}" to "-\\infty",
        )
        for ((raw, want) in cases) {
            val r = SolvePipeline.run(raw, DotMeaning.Multiply)
            assertTrue("$raw: $r", r is SolveResult.Solved)
            val l = (r as SolveResult.Solved).answers[0].latex
            val rhs = l.substringAfter("=").let { x -> if (x.contains("\\approx")) x.substringBefore("\\approx") else x }.let { x -> if (want.startsWith("\\frac")) x.substringBefore("=").let { it } else x }
            assertTrue("$raw -> $l (want $want)", l.contains("=$want"))
            assertFalse(rhs.isEmpty())
        }
    }
}
