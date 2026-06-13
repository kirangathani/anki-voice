package dev.kiran.ankivoice

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.Test

/**
 * Tier-3 instrumented UI tests. Drive the real app on an emulator with
 * UIAutomator: launch it, tap buttons, and assert the in-app log (Compose text,
 * exposed to the accessibility tree) reflects the expected behaviour.
 *
 * These run WITHOUT AnkiDroid installed, so they exercise only the paths that
 * are not gated on the AnkiDroid permission: app launch, the math/speech
 * pipeline ("Test math card"), speech text, and the mic-permission flow. The
 * AnkiDroid-gated buttons (List decks, Next due card, Sync, Dump columns) are
 * covered separately once a local test collection is provisioned.
 */
@RunWith(AndroidJUnit4::class)
class SpikeUiTest {

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchApp() {
        // If AnkiDroid is installed (CI), pre-grant its DB permission so the app
        // starts without a permission dialog. No-op if the permission is not
        // defined (AnkiDroid absent), which keeps non-AnkiDroid runs working.
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(PKG, ANKI_PERMISSION)
        } catch (_: Exception) {
            // permission not defined / not grantable; fine
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), LAUNCH_TIMEOUT)
    }

    @Test
    fun appLaunches_showsTitleAndMathPipelineReady() {
        assertNotNull(
            "title 'anki-voice spike' should be visible",
            device.wait(Until.hasObject(By.text("anki-voice spike")), LAUNCH_TIMEOUT),
        )
        // The math pipeline warms up asynchronously and logs when ready.
        assertNotNull(
            "log should report 'MathPipeline ready.'",
            device.wait(Until.hasObject(By.textContains("MathPipeline ready")), READY_TIMEOUT),
        )
    }

    /**
     * "Test math card" is the only end-to-end functional path that does not need
     * AnkiDroid: it runs the math pipeline + speech generation on a synthetic
     * card, then injects it (which renders a card with a "Show answer" button).
     * Tapping it and seeing "Show answer" appear proves that whole chain works.
     */
    @Test
    fun testMathCard_injectsCardWithShowAnswer() {
        // Button is disabled until the math pipeline is ready; wait for enabled.
        val btn = scrollToEnabled("Test math card")
        assertNotNull("'Test math card' button should become enabled", btn)
        btn!!.click()
        assertNotNull(
            "tapping 'Test math card' should inject a card showing 'Show answer'",
            device.wait(Until.hasObject(By.text("Show answer")), CARD_TIMEOUT),
        )
    }

    /**
     * "Grant mic" requests RECORD_AUDIO via the system dialog; granting it
     * flips the button label to "Test mic". Exercises the mic-permission flow
     * (a prerequisite for the voice/STT features). Tolerates the permission
     * already being granted from a prior test in the run.
     */
    @Test
    fun grantMic_grantsPermissionAndSwitchesToTestMic() {
        if (device.findObject(By.text("Test mic")) != null) return // already granted
        val grant = scrollTo("Grant mic")
        assertNotNull("'Grant mic' button should be present", grant)
        grant!!.click()
        val allow = device.wait(
            Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button")),
            6_000,
        )
            ?: device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 3_000)
            ?: device.wait(Until.findObject(By.textContains("While using")), 3_000)
            ?: device.wait(Until.findObject(By.text("Allow")), 3_000)
        assertNotNull("system permission Allow button should appear", allow)
        allow!!.click()
        assertNotNull(
            "button should switch to 'Test mic' after granting",
            device.wait(Until.hasObject(By.text("Test mic")), 8_000),
        )
    }

    /**
     * After a card is injected, "Show speech text" writes the generated speech
     * for the question and answer into the log ("speechQ:" / "speechA:"). This
     * asserts that output appears, covering the speech-rendering path.
     */
    @Test
    fun showSpeechText_writesSpeechToLog() {
        val testCard = scrollToEnabled("Test math card")
        assertNotNull("'Test math card' should enable", testCard)
        testCard!!.click()
        assertNotNull(
            "card should inject",
            device.wait(Until.hasObject(By.text("Show answer")), CARD_TIMEOUT),
        )
        // The injected card's MathView (WebView) recomposes asynchronously
        // (setHeight), which can move buttons after they're located. Re-find and
        // re-tap until the expected log appears, rather than caching one object.
        device.waitForIdle()
        assertTrue(
            "logcat should contain 'speechQ:' after Show speech text",
            tapUntilLog("Show speech text", "speechQ:"),
        )
    }

    /**
     * Re-finds [buttonText] and taps it, retrying until any of [expect] appears
     * in logcat. Re-finding each attempt makes it robust to recomposition that
     * moves buttons (e.g. the card's WebView resizing).
     */
    private fun tapUntilLog(buttonText: String, vararg expect: String, attempts: Int = 5): Boolean {
        val wanted = expect.toList()
        repeat(attempts) {
            scrollTo(buttonText)?.click()
            if (logcatContains(wanted, 4_000)) return true
            device.waitForIdle()
        }
        return logcatContains(wanted, 2_000)
    }

    /**
     * AnkiDroid integration: with AnkiDroid installed and its DB permission
     * granted (in @Before), the app should detect the ContentProvider and
     * "List decks" should load the collection's decks. Skips when AnkiDroid is
     * not installed (e.g., local runs).
     */
    @Test
    fun listDecks_withAnkiDroid_reachableAndLoadsDecks() {
        assumeTrue("AnkiDroid not installed", isAnkiDroidInstalled())
        assertTrue(
            "AnkiDroid ContentProvider should be reachable at startup",
            logcatContains("AnkiDroid ContentProvider reachable: true", 15_000),
        )
        val listBtn = scrollToEnabled("List decks")
        assertNotNull("'List decks' should be enabled with AnkiDroid + permission", listBtn)
        listBtn!!.click()
        assertTrue(
            "'List decks' should load decks from AnkiDroid",
            logcatContains("Loaded", 12_000),
        )
    }

    /** "Sync now" broadcasts DO_SYNC to AnkiDroid; the app logs that it sent it. */
    @Test
    fun syncNow_sendsDoSyncBroadcast() {
        assumeTrue("AnkiDroid not installed", isAnkiDroidInstalled())
        assertTrue(
            "'Sync now' should broadcast DO_SYNC",
            tapUntilLog("Sync now", "DO_SYNC broadcast sent"),
        )
    }

    /** "Dump columns" queries the ContentProvider and logs the column names. */
    @Test
    fun dumpColumns_logsProviderColumns() {
        assumeTrue("AnkiDroid not installed", isAnkiDroidInstalled())
        assertTrue(
            "'Dump columns' should log provider columns",
            tapUntilLog("Dump columns", "Decks columns:"),
        )
    }

    /**
     * "Next due card" queries the selected deck. With AnkiDroid's fresh default
     * collection there are no due cards, so this asserts the query path runs and
     * either fetches a card or reports none due (graceful empty handling).
     */
    @Test
    fun nextDueCard_queriesSelectedDeck() {
        assumeTrue("AnkiDroid not installed", isAnkiDroidInstalled())
        assertTrue(
            "'List decks' should run the provider query",
            tapUntilLog("List decks", "Loaded"),
        )
        // A fresh AnkiDroid collection exposes no decks via the provider, so no
        // deck gets selected and "Next due card" stays disabled. Skip rather
        // than fail when there is no test data; the assertion below is exercised
        // once a deck/card fixture is provisioned (TODO: committed .apkg import).
        assumeTrue(
            "AnkiDroid collection has no selectable deck (no test data)",
            !readSpikeLog().contains("Selected: <none>"),
        )
        assertTrue(
            "'Next due card' should fetch a card or report none due",
            tapUntilLog("Next due card", "Fetched card", "No more due cards"),
        )
    }

    /**
     * On the synthetic test card, "Show answer" reveals the answer (the button
     * disappears and the answer view + "Read answer" replace it). No AnkiDroid.
     */
    @Test
    fun showAnswer_revealsAnswerOnTestCard() {
        val testCard = scrollToEnabled("Test math card")
        assertNotNull("'Test math card' should enable", testCard)
        testCard!!.click()
        assertNotNull(
            "card should show 'Show answer'",
            device.wait(Until.hasObject(By.text("Show answer")), CARD_TIMEOUT),
        )
        assertTrue(
            "tapping 'Show answer' should reveal the answer (button disappears)",
            tapUntilGone("Show answer"),
        )
    }

    /** Re-finds [buttonText] and taps until it disappears (its action removed it). */
    private fun tapUntilGone(buttonText: String, attempts: Int = 5): Boolean {
        repeat(attempts) {
            val obj = scrollTo(buttonText) ?: return true
            obj.click()
            if (device.wait(Until.gone(By.text(buttonText)), 3_000)) return true
            device.waitForIdle()
        }
        return device.findObject(By.text(buttonText)) == null
    }

    private fun isAnkiDroidInstalled(): Boolean {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        return pm.getLaunchIntentForPackage("com.ichi2.anki") != null
    }

    /**
     * Polls the app's own logcat (tag SpikeLog) for [substr]. The instrumented
     * test shares the app's UID, so it can read the app's log entries, which is
     * far more reliable than scraping the nested-scroll Compose log pane.
     */
    private fun logcatContains(substr: String, timeoutMs: Long): Boolean =
        logcatContains(listOf(substr), timeoutMs)

    private fun logcatContains(subs: List<String>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = readSpikeLog()
            if (subs.any { text.contains(it) }) return true
            Thread.sleep(400)
        }
        return subs.any { readSpikeLog().contains(it) }
    }

    private fun readSpikeLog(): String = try {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "SpikeLog:I"))
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        text
    } catch (_: Exception) {
        ""
    }

    /** Finds [text], scrolling the screen up to a few times if it is off-screen. */
    private fun scrollTo(text: String): UiObject2? {
        repeat(6) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 10,
            )
            device.waitForIdle()
        }
        return device.findObject(By.text(text))
    }

    /** Waits for an enabled element with [text], scrolling it into view. */
    private fun scrollToEnabled(text: String): UiObject2? {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val obj = scrollTo(text)
            if (obj != null && obj.isEnabled) return obj
            device.waitForIdle()
        }
        return device.findObject(By.text(text))?.takeIf { it.isEnabled }
    }

    companion object {
        private const val PKG = "dev.kiran.ankivoice"
        private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val LAUNCH_TIMEOUT = 15_000L

        /**
         * Seed one Basic note into AnkiDroid (once) so "Next due card" has a real
         * card to fetch. Best-effort: guarded so a failure never breaks the suite
         * (nextDueCard also accepts the no-cards outcome). Uses AnkiDroid's
         * FlashCardsContract note/model URIs with the granted DB permission.
         */
        @BeforeClass
        @JvmStatic
        fun seedTestCard() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            if (ctx.packageManager.getLaunchIntentForPackage("com.ichi2.anki") == null) return
            try {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(PKG, ANKI_PERMISSION)
                val cr = ctx.contentResolver
                val modelsUri = Uri.parse("content://com.ichi2.anki.flashcards/models")
                var modelId = -1L
                cr.query(modelsUri, null, null, null, null)?.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val nameIdx = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        if (name.startsWith("Basic")) {
                            modelId = c.getLong(idIdx)
                            break
                        }
                        if (modelId == -1L) modelId = c.getLong(idIdx)
                    }
                }
                if (modelId <= 0) return
                val values = ContentValues().apply {
                    put("mid", modelId)
                    put("flds", "AnkiVoice test frontAnkiVoice test back")
                    put("tags", "ankivoice-test")
                }
                cr.insert(Uri.parse("content://com.ichi2.anki.flashcards/notes"), values)
            } catch (_: Exception) {
                // best-effort; nextDueCard tolerates an empty deck
            }
        }
        private const val READY_TIMEOUT = 40_000L
        private const val CARD_TIMEOUT = 45_000L
    }
}
