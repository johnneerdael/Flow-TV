package io.github.aedev.flow.ui.tv

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvLibraryNavigationDeviceTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Test fun libraryHasOnePlaylistTabAndDpadOpensMergedLikedSongs() {
        hilt.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(
            Intent()
                .setClassName(context.packageName, "io.github.aedev.flow.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("Library")), 15_000))
            device.findObject(By.desc("Library")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Playlists")), 10_000))
            assertEquals(1, device.findObjects(By.text("Playlists")).size)
            assertFalse(device.hasObject(By.text("Likes")))
            device.findObject(By.text("Playlists")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Liked songs")), 10_000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("Back")), 5_000))
            assertFalse(device.hasObject(By.text("All providers")))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("All providers")), 5_000))
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "library-navigation.png")))
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.externalCacheDir, "library-navigation-failed.png"))
            device.dumpWindowHierarchy(File(context.externalCacheDir, "library-navigation-failed.xml"))
            throw error
        } finally {
            device.pressHome()
        }
    }
}
