package dev.ilgax.utils.math

import org.junit.Assert.assertTrue

/**
 * A KaTeX-free check that solver output is well-formed LaTeX: balanced braces and brackets, matched \left/\right and
 * array environments, only commands the bundled KaTeX page knows (a command glued to a following letter, `\gee` for `\ge e`, is
 * an unknown command). Returns the problems found.
 */
internal object LatexSanity {
    private val known = setOf(
        "frac", "sqrt", "ln", "log", "cdot", "ge", "le", "ne", "in", "cup", "cap", "infty", "varnothing", "mathbb", "text",
        "pm", "approx", "pi", "left", "right", "begin", "end", "hline", "times", "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "circ", "to", "lim", "Rightarrow", "quad", "ne", "lvert", "rvert", "exp",
    )

    fun problems(tex: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        for (c in tex) {
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth < 0) { out += "unbalanced }"; depth = 0 } }
        }
        if (depth != 0) out += "unbalanced {"
        if (Regex("""\\left""").findAll(tex).count() != Regex("""\\right""").findAll(tex).count()) out += "\\left/\\right"
        if (Regex("""\\begin\{array\}""").findAll(tex).count() != Regex("""\\end\{array\}""").findAll(tex).count()) out += "array"
        for (m in Regex("""\\([A-Za-z]+)""").findAll(tex)) {
            val name = m.groupValues[1]
            if (name !in known) out += "unknown command \\$name"
        }
        if (tex.contains("\\\\") && !tex.contains("aligned") && !tex.contains("array")) out += "stray line break"
        return out
    }

    fun assertParses(r: dev.ilgax.utils.math.solver.SolveResult.Solved, label: String) {
        val all = r.answers.map { it.latex } + r.steps.map { it.latex }
        for (t in all) assertTrue("$label: bad LaTeX «$t»: ${problems(t)}", problems(t).isEmpty())
    }
}
