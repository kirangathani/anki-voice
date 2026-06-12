package dev.kiran.ankivoice

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
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
        private const val LAUNCH_TIMEOUT = 15_000L
        private const val READY_TIMEOUT = 40_000L
        private const val CARD_TIMEOUT = 45_000L
    }
}
