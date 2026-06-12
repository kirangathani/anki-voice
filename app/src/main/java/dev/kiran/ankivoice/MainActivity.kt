package dev.kiran.ankivoice

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.kiran.ankivoice.anki.AnkiContract
import dev.kiran.ankivoice.anki.AnkiRepository
import dev.kiran.ankivoice.anki.Deck
import dev.kiran.ankivoice.anki.DueCard
import dev.kiran.ankivoice.math.MathPipeline
import dev.kiran.ankivoice.math.MathView
import dev.kiran.ankivoice.voice.LlmSpeechConverter
import dev.kiran.ankivoice.voice.SpeechCache
import dev.kiran.ankivoice.voice.SttEngine
import dev.kiran.ankivoice.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpikeScreen()
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()
    val append: (String) -> Unit = remember {
        { line ->
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logState.value = logState.value + "$ts  $line"
            // Mirror every log line to logcat under a stable tag. Useful for
            // on-device debugging and lets instrumented tests assert on log
            // content without scraping the nested-scroll Compose log pane.
            android.util.Log.i("SpikeLog", line)
        }
    }

    // Single MathPipeline for the app's lifetime — owns one hidden WebView.
    // Pass `append` as the diagnostic sink so JS console + bridge events surface in our UI log.
    val mathPipeline = remember { MathPipeline(context, onLog = append) }
    val tts = remember { TtsEngine(context, onLog = append) }
    val stt = remember { SttEngine(context, onLog = append) }
    val llmSpeech = remember {
        val key = BuildConfig.ANTHROPIC_API_KEY
        if (key.isNotEmpty()) {
            append("[llm] Claude Haiku 4.5 enabled (key length=${key.length})")
            LlmSpeechConverter(key, SpeechCache(context), onLog = append)
        } else {
            append("[llm] no ANTHROPIC_API_KEY set — speech will use SRE fallback")
            null
        }
    }
    val repo = remember { AnkiRepository(context, mathPipeline, llmSpeech, onLog = append) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, AnkiContract.PERMISSION_READ_WRITE)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        append(if (granted) "Permission granted." else "Permission DENIED — app cannot proceed.")
    }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        append(if (granted) "Microphone permission granted." else "Microphone permission DENIED.")
    }

    var decks by remember { mutableStateOf<List<Deck>>(emptyList()) }
    var selectedDeck by remember { mutableStateOf<Deck?>(null) }
    var currentCard by remember { mutableStateOf<DueCard?>(null) }
    var revealAnswer by remember { mutableStateOf(false) }
    var cardStartedAtMs by remember { mutableStateOf(0L) }
    var mathPipelineReady by remember { mutableStateOf(false) }

    fun fetchNextCard(deck: Deck) {
        scope.launch {
            try {
                val card = withContext(Dispatchers.IO) { repo.nextDueCard(deck.id) }
                currentCard = card
                revealAnswer = false
                cardStartedAtMs = System.currentTimeMillis()
                if (card == null) {
                    append("No more due cards for '${deck.name}'.")
                } else {
                    append("Fetched card noteId=${card.noteId} ord=${card.cardOrd} buttons=${card.buttonCount}")
                }
            } catch (t: Throwable) {
                append("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun grade(card: DueCard, ease: Int, label: String) {
        val deck = selectedDeck ?: return
        val timeTaken = (System.currentTimeMillis() - cardStartedAtMs).coerceAtLeast(0L)
        scope.launchIo(append) {
            val rows = repo.submitReview(card, ease, timeTaken)
            append("Graded '$label' (ease=$ease, time=${timeTaken}ms). Rows updated: $rows.")
        }
        fetchNextCard(deck)
    }

    LaunchedEffect(Unit) {
        append("Spike starting. build=${BuildConfig.GIT_SHA} at ${BuildConfig.BUILD_TIME}")

        // Warm the math pipeline early so it's ready by the time a card needs processing.
        scope.launch {
            try {
                mathPipeline.warmUp()
                mathPipelineReady = true
                append("MathPipeline ready.")
            } catch (t: Throwable) {
                append("MathPipeline FAILED: ${t.message}")
            }
        }

        val available = repo.isAnkiDroidAvailable()
        append("AnkiDroid ContentProvider reachable: $available")
        if (!available) {
            append("AnkiDroid not installed (or package visibility misconfigured). Stop.")
            return@LaunchedEffect
        }
        if (!permissionGranted) {
            append("Requesting AnkiDroid READ_WRITE_DATABASE permission...")
            permissionLauncher.launch(AnkiContract.PERMISSION_READ_WRITE)
        } else {
            append("Permission already granted.")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("anki-voice spike", style = MaterialTheme.typography.headlineSmall)
        Text(
            "build ${BuildConfig.GIT_SHA}  ·  ${BuildConfig.BUILD_TIME}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF666666),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = permissionGranted,
                onClick = {
                    scope.launchIo(append) {
                        val result = repo.listDecks()
                        decks = result
                        selectedDeck = result.firstOrNull { !it.name.equals("Default", ignoreCase = true) }
                            ?: result.firstOrNull()
                        append("Loaded ${result.size} decks. Selected: ${selectedDeck?.name ?: "<none>"}")
                        result.take(10).forEach { append("  - ${it.id}: ${it.name}") }
                    }
                },
            ) { Text("List decks") }

            Button(
                enabled = permissionGranted && selectedDeck != null && mathPipelineReady,
                onClick = { selectedDeck?.let(::fetchNextCard) },
            ) { Text("Next due card") }
        }

        currentCard?.let { card ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Q:", style = MaterialTheme.typography.labelMedium)
                    MathView(
                        html = card.displayHtmlQuestion,
                        modifier = Modifier.fillMaxWidth(),
                        onLog = append,
                    )
                    if (revealAnswer) {
                        Text("A:", style = MaterialTheme.typography.labelMedium)
                        MathView(
                            html = card.displayHtmlAnswer,
                            modifier = Modifier.fillMaxWidth(),
                            onLog = append,
                        )
                    } else {
                        Button(onClick = { revealAnswer = true }) { Text("Show answer") }
                    }
                }
            }

            if (revealAnswer) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GradeButton("Again") { grade(card, AnkiContract.Ease.AGAIN, "Again") }
                    GradeButton("Hard") { grade(card, AnkiContract.Ease.HARD, "Hard") }
                    GradeButton("Good") { grade(card, AnkiContract.Ease.GOOD, "Good") }
                    GradeButton("Easy") { grade(card, AnkiContract.Ease.EASY, "Easy") }
                }
            }
        }

        // Diagnostic row: sync, dump columns, math test, show speech.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = permissionGranted,
                onClick = {
                    scope.launchIo(append) {
                        repo.requestSync()
                        append("DO_SYNC broadcast sent. Check AnkiDroid for sync activity.")
                    }
                },
            ) { Text("Sync now") }

            Button(
                enabled = permissionGranted,
                onClick = {
                    scope.launchIo(append) {
                        append("Decks columns: ${repo.debugColumnsFor(AnkiContract.Deck.CONTENT_ALL_URI)}")
                        append("Schedule columns: ${repo.debugColumnsFor(AnkiContract.ReviewInfo.CONTENT_URI)}")
                        currentCard?.let { c ->
                            append("Card columns: ${repo.debugColumnsFor(AnkiContract.Card.uri(c.noteId, c.cardOrd))}")
                        }
                    }
                },
            ) { Text("Dump columns") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = mathPipelineReady,
                onClick = {
                    scope.launch {
                        try {
                            val q = """The Equity Capital Allocation: $$\frac{a_L \times \frac{B}{C}}{\text{Cov}_{a,b}}$$ Describe the formula."""
                            val a = """It's the fraction with numerator a-sub-L times B over C, denominator covariance of a and b. Also note: \(\sigma^2 = E[(X - \mu)^2]\)."""
                            val speechQ = repo.generateSpeech(q)
                            val speechA = repo.generateSpeech(a)
                            currentCard = DueCard(
                                noteId = -1L,
                                cardOrd = 0,
                                buttonCount = 4,
                                displayHtmlQuestion = q,
                                displayHtmlAnswer = a,
                                speechQuestion = speechQ,
                                speechAnswer = speechA,
                            )
                            revealAnswer = false
                            cardStartedAtMs = System.currentTimeMillis()
                            append("Test math card injected. Grade buttons won't submit (synthetic noteId).")
                        } catch (t: Throwable) {
                            append("ERROR injecting test card: ${t.message}")
                        }
                    }
                },
            ) { Text("Test math card") }

            Button(
                enabled = currentCard != null,
                onClick = {
                    val c = currentCard ?: return@Button
                    append("speechQ: ${tts.stripMarkers(c.speechQuestion)}")
                    append("speechA: ${tts.stripMarkers(c.speechAnswer)}")
                },
            ) { Text("Show speech text") }
        }

        // Read aloud reads only what's currently on screen — question if the
        // answer hasn't been revealed, answer if it has. No auto-continue.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = currentCard != null,
                onClick = {
                    val c = currentCard ?: return@Button
                    val toSpeak = if (revealAnswer) c.speechAnswer else c.speechQuestion
                    scope.launch {
                        try {
                            tts.speak(toSpeak)
                        } catch (t: Throwable) {
                            append("TTS error: ${t.message}")
                        }
                    }
                },
            ) { Text(if (revealAnswer) "Read answer" else "Read question") }

            Button(
                enabled = currentCard != null,
                onClick = { tts.stop() },
            ) { Text("Stop") }
        }

        // Stage-1 STT diagnostic: request mic permission, capture one
        // utterance, log the transcript. Confirms SpeechRecognizer works on
        // this device before we build the ReviewSession state machine.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!micGranted) {
                        append("Requesting microphone permission...")
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        scope.launch {
                            append("Listening — say 'execute' when you're done.")
                            val r = stt.listen(
                                wakeWords = listOf("execute"),
                            )
                            when (r) {
                                is SttEngine.Result.Recognized ->
                                    append("Mic transcript: '${r.transcript}'")
                                is SttEngine.Result.NoMatch ->
                                    append("Mic: no speech recognised.")
                                is SttEngine.Result.Error ->
                                    append("Mic error: ${r.message} (code=${r.code})")
                            }
                        }
                    }
                },
            ) { Text(if (micGranted) "Test mic" else "Grant mic") }
        }

        Text("Log", style = MaterialTheme.typography.labelLarge)
        LogPane(log, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RowScope.GradeButton(label: String, onClick: () -> Unit) {
    Button(modifier = Modifier.weight(1f), onClick = onClick) { Text(label) }
}

@Composable
private fun LogPane(lines: List<String>, modifier: Modifier = Modifier) {
    val scrollState: ScrollState = rememberScrollState()
    LaunchedEffect(lines.size) { scrollState.animateScrollTo(scrollState.maxValue) }
    Column(
        modifier
            .height(280.dp)
            .background(Color(0xFF111111))
            .padding(8.dp)
            .verticalScroll(scrollState),
    ) {
        lines.forEach { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFCCCCCC),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

private fun CoroutineScope.launchIo(
    append: (String) -> Unit,
    block: suspend () -> Unit,
) {
    launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (t: Throwable) {
            append("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
