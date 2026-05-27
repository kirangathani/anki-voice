# anki-voice — outstanding work

Living list of stuff that's been requested but not yet built. Cross-reference: `CLAUDE.md` for architecture overview, `/home/kiran/.claude/plans/can-we-please-build-sunny-rabin.md` for the canonical design plan.

## Voice commands (hands-free flow)

The user must be able to say these aloud during a review session, and the app must do the right thing without taps:

- **Grade the answer** — "again", "hard", "good", "easy". Also accept "1", "2", "3", "4" as numeric synonyms.
- **Repeat the question** — full re-read of the current card's front.
- **Repeat the equation** — re-read only the math portion(s) of the current card (skip the surrounding prose). Useful when the formula went past too quickly and the user wants only that part again.
- **Repeat / give the answer** — read the card's back. Two phrasings: "what was the answer?" (give it to me) and "repeat the answer" (after it was already read once).

Open design questions:
- Intent classification: keyword match vs LLM intent classification (use Claude Haiku to parse loose phrasings like "say it again" / "go back" / "yeah okay easy one")? Keyword match is fast/offline; LLM is more forgiving. Probably keyword first, LLM later.
- Wake word vs always-listening vs push-to-talk? Default is probably "always listening between TTS utterances" since the user's hands are busy.
- Endpoint detection — Android `SpeechRecognizer` cuts off after ~2s silence by default. Risk: user pauses to think mid-grade-word → cut off. Solution: tunable threshold + a "still thinking" wake phrase.

Implementation depends on `SttEngine` (task #14) and `ReviewSession` (task #15) in the active task list.

## Already done (recent)

- v0 spike proves AnkiDroid Content Provider primitives end-to-end.
- MathJax visual rendering in MathView per card.
- LLM-based math → speech via Claude Haiku 4.5 with prompt caching + SharedPreferences result cache.
- TTS playback with per-chunk rate (prose vs math).
- GitHub Actions builds APK on every push, publishes to rolling `latest-debug` release.
- CI injects `ANTHROPIC_API_KEY` GitHub secret into `local.properties` for `BuildConfig.ANTHROPIC_API_KEY`.
