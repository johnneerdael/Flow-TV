package io.github.aedev.flow.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.plugin.install.PluginInstallException
import io.github.aedev.flow.plugin.install.PluginInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PluginDownloadInstallTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var installer: PluginInstaller

    @Before
    fun inject() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePluginDownloads") == "true")
        hiltRule.inject()
    }

    @Test
    fun registeredCodesFetchVerifiedPackagesFromBuzzheavier() =
        runBlocking {
            val expected = mapOf("102" to "nl.neerdael.beatport", "772" to "nl.neerdael.spotify", "416" to "nl.neerdael.youtube-music")
            expected.forEach { (code, id) ->
                val pending = installer.fetch(code)
                assertEquals(id, pending.pack.manifest.id)
                assertTrue(pending.pack.signerFingerprint.isNotBlank())
                assertTrue(pending.pack.files.containsKey("plugin.js"))
            }
        }

    @Test
    fun unknownCodeFailsBeforeDownloading() {
        val failure = assertThrows(PluginInstallException::class.java) { runBlocking { installer.fetch("000") } }
        assertEquals(io.github.aedev.flow.R.string.tv_plugins_code_unknown, failure.messageResource)
    }
}
