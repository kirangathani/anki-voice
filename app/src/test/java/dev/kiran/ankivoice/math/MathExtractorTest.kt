package dev.kiran.ankivoice.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathExtractorTest {

    @Test
    fun extractsDoubleDollarBlock() {
        assertEquals(listOf("x + 1"), MathExtractor.extractMath("Compute $$x + 1$$ now."))
    }

    @Test
    fun extractsInlineParenDelimiters() {
        assertEquals(listOf("a_L"), MathExtractor.extractMath("""The value \(a_L\) here."""))
    }

    @Test
    fun extractsBracketDisplayDelimiters() {
        assertEquals(listOf("E = mc^2"), MathExtractor.extractMath("""Energy \[E = mc^2\]."""))
    }

    @Test
    fun extractsLatexTagDelimiters() {
        assertEquals(listOf("\\beta"), MathExtractor.extractMath("Coefficient [latex]\\beta[/latex]."))
    }

    @Test
    fun multipleSegmentsReturnedInSourceOrder() {
        val text = """First \(a\) then $$b + c$$ then \[d\]."""
        assertEquals(listOf("a", "b + c", "d"), MathExtractor.extractMath(text))
    }

    @Test
    fun proseOnlyCardReturnsEmptyList() {
        assertEquals(emptyList<String>(), MathExtractor.extractMath("Just plain prose, no math."))
    }

    @Test
    fun mixedProseAndMathReturnsOnlyMath() {
        val text = "The formula is $$\\frac{a}{b}$$ which describes the ratio."
        assertEquals(listOf("\\frac{a}{b}"), MathExtractor.extractMath(text))
    }

    @Test
    fun blankDelimitedBlockIsIgnored() {
        assertEquals(emptyList<String>(), MathExtractor.extractMath("Empty $$   $$ block."))
    }

    @Test
    fun hasMathReflectsPresence() {
        assertTrue(MathExtractor.hasMath("a $$x$$ b"))
        assertFalse(MathExtractor.hasMath("no math here"))
    }
}
