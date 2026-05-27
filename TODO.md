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

## Future upgrade: replace Android built-in TTS with a hosted voice model

Current architecture (as of `357852c`):
- **Claude Haiku 4.5** writes speech-ready text from card HTML+LaTeX. Output looks like `"A. L. all over the covariance of A. and B."` — period-padded letters, explicit pauses.
- **Android built-in `TextToSpeech`** reads that text aloud through the phone speaker. This is the weak link: mispronounces single letters as articles, ignores most punctuation, needs explicit `pauseMs` silence injection between every period to sound passable.

The text-generation half (Claude) is fine. The voice-synthesis half is what's holding playback back. Hosted TTS services are dramatically better on technical content.

**Candidate replacements (Claude Haiku stays; only the voice synthesizer changes):**

| Provider / Model | Approx cost @ ~450k chars/month | Quality vs Android TTS | Notes |
|---|---|---|---|
| OpenAI `gpt-4o-mini-tts` | ~$1/month | Dramatically better | New (2025). Best $/quality. **First option to try.** |
| Google Cloud WaveNet | Free under 1M chars/mo | Noticeably better | Decent but less natural than OpenAI. Most setup friction (Google Cloud project + service account). |
| OpenAI `tts-1` | ~$7/month | Very good | Older, mature, well-tested. |
| ElevenLabs `eleven_flash_v2_5` | ~$20-30/month | Excellent — handles subscripts/abbreviations natively | User has rejected as too expensive. |
| ElevenLabs `eleven_multilingual_v2` | $50+/month | Best naturalness | Overkill for this app. |

**Implementation sketch when we get to it:**
- New `RemoteTtsEngine` class parallel to existing `TtsEngine`. Takes API key in constructor.
- POST text → receive MP3 bytes → play via Android `MediaPlayer`.
- Cache MP3 bytes to app's cache directory keyed by SHA-256 hash of text (mirror `SpeechCache` pattern). Repeat cards = no API call.
- Same `BuildConfig.OPENAI_API_KEY` (or similar) pattern as we use for `ANTHROPIC_API_KEY`. GitHub secret + workflow injection.
- Keep current `TtsEngine` as offline fallback when remote call fails or key absent.

## Future upgrade: replace Android `SpeechRecognizer` with Wispr Flow (or similar) for voice commands

User has a paid Wispr Flow subscription. Worth investigating whether we can leverage it for the hands-free grading flow instead of Android's built-in `SpeechRecognizer` (which is unreliable on long utterances and aggressive about endpointing).

**Findings from initial research:**
- Wispr Flow on Android is a dictation app using the system Accessibility Service. It injects transcribed text into any focused text field.
- It does NOT expose a public API/SDK for third-party apps to embed it directly.
- Possible integration paths:
  1. Have a (hidden?) text field in our review screen that Flow can dictate into; we read its content to detect grade words. Awkward UX but works.
  2. Use the same upstream model Wispr is built on — they use OpenAI Whisper variants. We could call OpenAI Whisper API directly for STT (~$0.006/min, paid via the OpenAI API key we'd already have for TTS).
  3. Use Whisper running locally (ONNX/TFLite). Heavier APK but truly offline.

**Recommendation when we get to it:** if user pays for Flow anyway, try path 1 first (zero new API cost). If awkward, fall back to OpenAI Whisper API.

References:
- Wispr Flow Android: https://wisprflow.ai/android
- OpenAI Whisper API: https://platform.openai.com/docs/guides/speech-to-text

## Already done (recent)

- v0 spike proves AnkiDroid Content Provider primitives end-to-end.
- MathJax visual rendering in MathView per card.
- LLM-based math → speech via Claude Haiku 4.5 with prompt caching + SharedPreferences result cache.
- Android built-in TTS playback with one tunable `pauseMs` (currently 120ms) that injects explicit silence at every period/colon/semicolon/?/!/newline. Single knob controls pacing.
- GitHub Actions builds APK on every push, publishes to rolling `latest-debug` release.
- CI injects `ANTHROPIC_API_KEY` GitHub secret into `local.properties` for `BuildConfig.ANTHROPIC_API_KEY`.
