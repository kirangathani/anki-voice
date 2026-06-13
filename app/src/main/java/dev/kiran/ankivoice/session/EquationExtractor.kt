package dev.kiran.ankivoice.session

/**
 * Pulls LaTeX equation source out of raw card HTML.
 *
 * Recognizes the same delimiters as the speech pipeline (`math/pipeline.html`):
 * `$$...$$`, `[latex]...[/latex]`, `\(...\)`, and `\[...\]`.
 *
 * Limitation (v1): this returns the raw LaTeX source, not the ClearSpeak prose
 * the [dev.kiran.ankivoice.math.MathPipeline] / LLM would produce. Isolating
 * just the converted math speech needs the speech converter, which the
 * ReviewSession does not hold, so it is deferred. The session uses
 * [hasEquation] to decide whether a card has math to repeat.
 */
object EquationExtractor {

    private val patterns = listOf(
        Regex("""\$\$([\s\S]+?)\$\$"""),
        Regex("""\[latex]([\s\S]+?)\[/latex]""", RegexOption.IGNORE_CASE),
        Regex("""\\\(([\s\S]+?)\\\)"""),
        Regex("""\\\[([\s\S]+?)\\]"""),
    )

    /** Returns each LaTeX block found in [html], delimiters stripped, in order. */
    fun equations(html: String): List<String> =
        patterns.flatMap { re ->
            re.findAll(html).map { it.groupValues[1].trim() }
        }.filter { it.isNotEmpty() }

    /** True if [html] contains at least one LaTeX block. */
    fun hasEquation(html: String): Boolean = equations(html).isNotEmpty()
}
