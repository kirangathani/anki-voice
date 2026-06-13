package dev.kiran.ankivoice.math

/**
 * Pulls the math (LaTeX) segments out of a card's text, dropping the
 * surrounding prose. Used by the "repeat the equation" voice command so the
 * user can hear only the formula again.
 *
 * Recognizes the same delimiters as the speech pipeline (`math/pipeline.html`):
 * `$$...$$`, `[latex]...[/latex]`, `\(...\)`, and `\[...\]`.
 *
 * Pure Kotlin (no android.* imports) so it is JVM-unit-testable.
 */
object MathExtractor {

    private val patterns = listOf(
        Regex("""\$\$([\s\S]+?)\$\$"""),
        Regex("""\[latex]([\s\S]+?)\[/latex]""", RegexOption.IGNORE_CASE),
        Regex("""\\\(([\s\S]+?)\\\)"""),
        Regex("""\\\[([\s\S]+?)\\]"""),
    )

    /**
     * Returns each LaTeX block found in [cardHtmlOrText], delimiters stripped,
     * in source order. Empty list when the card carries no math.
     */
    fun extractMath(cardHtmlOrText: String): List<String> =
        patterns.flatMap { re ->
            re.findAll(cardHtmlOrText).map { match ->
                IndexedValue(match.range.first, match.groupValues[1].trim())
            }
        }
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.index }
            .map { it.value }

    /** True if [cardHtmlOrText] contains at least one LaTeX block. */
    fun hasMath(cardHtmlOrText: String): Boolean = extractMath(cardHtmlOrText).isNotEmpty()
}
