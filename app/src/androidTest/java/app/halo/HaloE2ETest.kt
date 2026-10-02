package app.halo

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app against tools/fake_bulb.py running on the host machine
 * (reachable from the emulator at 10.0.2.2) and saves screenshots for review.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HaloE2ETest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private var shot = 0

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
        shot++
        val file = "/data/local/tmp/halo-shots/%02d-%s.png".format(shot, name)
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("mkdir -p /data/local/tmp/halo-shots").close()
        ui.executeShellCommand("screencap -p $file").close()
        Thread.sleep(500)
    }

    @Test
    fun setupAndControlTheLight() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("bulbHost") ?: "10.0.2.2"
        val version = args.getString("bulbVersion") ?: "3.3"

        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("field-device-id")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("welcome")

        rule.onNodeWithTag("field-name").performScrollTo().performTextReplacement("Bedroom light")
        rule.onNodeWithTag("field-device-id").performScrollTo().performTextReplacement("bf0123456789abcdefgh01")
        rule.onNodeWithTag("field-host").performScrollTo().performTextReplacement(host)
        rule.onNodeWithTag("field-local-key").performScrollTo().performTextReplacement("0123456789abcdef")
        // Deliberately pick a wrong version to check auto-detection, unless testing 3.3 itself.
        rule.onNodeWithTag("chip-${if (version == "3.3") "3.5" else "3.3"}").performScrollTo().performClick()
        screenshot("setup-filled")
        rule.onNodeWithTag("btn-Connect & save").performScrollTo().performClick()

        rule.waitUntil(45_000) { rule.onAllNodes(hasTestTag("orb")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("Connected over Wi-Fi")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("light-white")

        rule.onNodeWithTag("power-Off").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("headline") and hasText("Off")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("light-off")

        rule.onNodeWithTag("orb").performScrollTo().performClick()
        rule.onNodeWithTag("slider-brightness").performScrollTo().performTouchInput { click(Offset(width * 0.35f, height / 2f)) }
        rule.onNodeWithTag("slider-warmth").performScrollTo().performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        screenshot("light-warm-dim")

        rule.onNodeWithTag("tab-Colour").performScrollTo().performClick()
        rule.onNodeWithTag("wheel").performScrollTo().performTouchInput { click(Offset(width * 0.85f, height * 0.6f)) }
        screenshot("colour")

        rule.onNodeWithTag("tab-Scenes").performScrollTo().performClick()
        screenshot("scenes")
        rule.onNodeWithTag("preset-Sunset").performScrollTo().performClick()
        rule.onNodeWithTag("preset-Aurora").performScrollTo().performClick()
        screenshot("scene-applied")

        rule.onNodeWithTag("tab-Effects").performScrollTo().performClick()
        rule.onNodeWithTag("effect-Breathe").performScrollTo().performClick()
        Thread.sleep(3500)
        screenshot("effect-playing")
        rule.onNodeWithTag("effect-Breathe").performScrollTo().performClick()

        rule.onNodeWithTag("nav-Schedules").performClick()
        screenshot("schedules-empty")
        rule.onNodeWithText("Sunrise wake-up").performScrollTo().performClick()
        screenshot("schedules")
        rule.onNodeWithTag("schedule-Wake up").performClick()
        screenshot("schedule-editor")
        rule.onNodeWithTag("btn-Save schedule").performScrollTo().performClick()

        rule.onNodeWithTag("nav-Settings").performClick()
        screenshot("settings")
        rule.onNodeWithTag("theme-MIDNIGHT").performClick()
        rule.onNodeWithText("Match my light").performClick()
        screenshot("settings-midnight")
        rule.onNodeWithTag("theme-AMOLED").performClick()

        rule.onNodeWithTag("nav-Light").performClick()
        rule.onNodeWithTag("tab-White").performScrollTo().performClick()
        rule.onNodeWithTag("slider-brightness").performScrollTo().performTouchInput { click(Offset(width * 0.98f, height / 2f)) }
        Thread.sleep(1500)
        screenshot("final")
    }
}
