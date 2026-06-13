package dev.kiran.ankivoice.session

/**
 * Anything that can classify a loose voice transcript into a [ReviewCommand].
 * Implemented by [LlmCommandClassifier] in production; faked in tests.
 *
 * Implementations must be graceful: if they cannot classify (no network, no
 * API key, ambiguous input) they return [ReviewCommand.Unknown] rather than
 * throwing.
 */
interface CommandClassifier {
    suspend fun classify(transcript: String): ReviewCommand
}

/**
 * Two-tier command resolution: fast offline keyword match first, optional LLM
 * intent classification second.
 *
 * The keyword [CommandParser] handles the common, terse cases instantly and
 * offline. Only when it returns [ReviewCommand.Unknown] -- a loose phrasing it
 * could not match ("say it again", "go back", "yeah okay that one was easy") --
 * do we consult the [classifier], and only if one is available.
 *
 * Keyword-first behaviour is fully preserved when [classifier] is null (the
 * default), so the resolver is a no-op wrapper in the offline / no-key case.
 */
class CommandResolver(
    private val classifier: CommandClassifier? = null,
    private val onLog: (String) -> Unit = {},
) {

    suspend fun resolve(transcript: String): ReviewCommand {
        val keyword = CommandParser.parse(transcript)
        if (keyword !is ReviewCommand.Unknown) return keyword

        // Keyword miss. Only fall through to the LLM when we have a classifier
        // and something non-blank worth classifying.
        if (classifier == null || transcript.isBlank()) return keyword

        onLog("CommandResolver: keyword miss, asking LLM")
        val llm = classifier.classify(transcript)
        if (llm is ReviewCommand.Unknown) {
            onLog("CommandResolver: LLM also Unknown")
        } else {
            onLog("CommandResolver: LLM resolved '$transcript'")
        }
        return llm
    }
}
