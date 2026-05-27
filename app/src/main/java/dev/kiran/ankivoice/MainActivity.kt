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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.RowScope
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
import dev.kiran.ankivoice.BuildConfig
import dev.kiran.ankivoice.anki.AnkiContract
import dev.kiran.ankivoice.anki.AnkiRepository
import dev.kiran.ankivoice.anki.Deck
import dev.kiran.ankivoice.anki.DueCard
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
    val repo = remember { AnkiRepository(context) }

    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()
    val append: (String) -> Unit = { line ->
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logState.value = logState.value + "$ts  $line"
    }

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

    var decks by remember { mutableStateOf<List<Deck>>(emptyList()) }
    var selectedDeck by remember { mutableStateOf<Deck?>(null) }
    var currentCard by remember { mutableStateOf<DueCard?>(null) }
    var revealAnswer by remember { mutableStateOf(false) }
    var cardStartedAtMs by remember { mutableStateOf(0L) }

    fun fetchNextCard(deck: Deck) {
        scope.launchIo(append) {
            val card = repo.nextDueCard(deck.id)
            currentCard = card
            revealAnswer = false
            cardStartedAtMs = System.currentTimeMillis()
            if (card == null) {
                append("No more due cards for '${deck.name}'.")
            } else {
                append("Fetched card noteId=${card.noteId} ord=${card.cardOrd} buttons=${card.buttonCount}")
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
                        // Auto-pick the first non-Default deck if possible.
                        selectedDeck = result.firstOrNull { !it.name.equals("Default", ignoreCase = true) }
                            ?: result.firstOrNull()
                        append("Loaded ${result.size} decks. Selected: ${selectedDeck?.name ?: "<none>"}")
                        result.take(10).forEach { append("  - ${it.id}: ${it.name}") }
                    }
                },
            ) { Text("List decks") }

            Button(
                enabled = permissionGranted && selectedDeck != null,
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
                    Text(card.question, style = MaterialTheme.typography.bodyLarge)
                    if (revealAnswer) {
                        Text("A:", style = MaterialTheme.typography.labelMedium)
                        Text(card.answer, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Button(onClick = { revealAnswer = true }) { Text("Show answer") }
                    }
                }
            }

            // Grade buttons only after the answer is revealed — matches Anki UX.
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
