package io.github.aedev.flow.ui.tv

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMusicFoldersReleaseSmokeTest {
    @Test fun authenticatedShareCanBeTestedSavedAndBrowsedInReleaseApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = InstrumentationRegistry.getArguments().getString("release_folder_fixture_host")
        assumeNotNull(host)
        val context = instrumentation.targetContext
        context.startActivity(
            Intent().setClassName("nl.neerdael.milkbeat", "io.github.aedev.flow.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.desc("Settings")), 15_000))
        device.findObject(By.desc("Settings")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Music folders")), 5_000))
        device.findObject(By.text("Music folders")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Add SMB share")), 5_000))
        device.findObject(By.text("Add SMB share")).click()
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
        val fields = device.findObjects(By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }
        assertTrue(fields.size >= 5)
        for ((index, value) in listOf("Release fixture", host!!, "1446", "Music").withIndex()) {
            fields[index].text = value
            device.waitForIdle(1_000)
        }
        val pane = device.findObjects(By.scrollable(true)).maxBy { it.visibleBounds.left }
        pane.scroll(Direction.DOWN, 1f)
        device.waitForIdle(1_000)
        val credentials = device.findObjects(By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }.takeLast(3)
        assertTrue(credentials.size == 3)
        credentials.first().text = "milkbeat-fixture"
        device.waitForIdle(1_000)
        credentials.last().text = "fixture-only-password"
        device.waitForIdle(1_000)
        pane.scroll(Direction.DOWN, 1f)
        assertNotNull(device.findObject(By.text("Test access")))
        device.findObject(By.text("Test access")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Access confirmed. This folder can be read.")), 25_000))
        pane.scroll(Direction.DOWN, 1f)
        device.findObject(By.text("Save")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Release fixture")), 5_000))
        device.findObject(By.desc("Library")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Folders")), 5_000))
        device.findObject(By.text("Folders")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Release fixture")), 5_000))
        device.findObject(By.textContains("Release fixture")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Album")), 15_000))
        device.findObject(By.text("Album")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("01 First")), 15_000))
        assertNotNull(device.findObject(By.textContains("02 Été #2")))
        device.findObject(By.text("Play folder")).click()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var playing = false
        while (!playing && SystemClock.elapsedRealtime() < deadline) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys media_session")
            val sessions = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            playing = "nl.neerdael.milkbeat" in sessions && "01 First" in sessions &&
                ("state=3" in sessions || "state=PLAYING" in sessions)
            if (!playing) SystemClock.sleep(200)
        }
        assertTrue("Release music session must play the folder track", playing)
        for (attempt in 0 until 3) {
            device.pressBack()
            if (device.wait(Until.hasObject(By.desc("Settings")), 2_000)) break
        }
        assertTrue(device.wait(Until.hasObject(By.desc("Settings")), 5_000))
        device.findObject(By.desc("Settings")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Music folders")), 5_000))
        device.findObject(By.text("Music folders")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Release fixture")), 5_000))
        device.findObject(By.textContains("Release fixture")).click()
        val editor = device.findObjects(By.scrollable(true)).maxBy { it.visibleBounds.left }
        for (attempt in 0 until 4) {
            editor.scroll(Direction.DOWN, 1f)
            if (device.wait(Until.hasObject(By.text("Remove folder")), 1_000)) break
        }
        assertTrue(device.wait(Until.hasObject(By.text("Remove folder")), 5_000))
        device.findObject(By.text("Remove folder")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Folder removed")), 5_000))
    }
}
