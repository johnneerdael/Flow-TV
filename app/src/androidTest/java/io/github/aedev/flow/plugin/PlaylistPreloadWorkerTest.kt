package io.github.aedev.flow.plugin

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.plugin.preload.PlaylistPreloadEntryPoint
import io.github.aedev.flow.plugin.preload.PlaylistPreloadWorker
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaylistPreloadWorkerTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Test
    fun workerConstructsThroughWorkManagerAndHilt() =
        runBlocking {
            hilt.inject()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val entry = EntryPointAccessors.fromApplication(context, PlaylistPreloadEntryPoint::class.java)
            assertNotNull(entry.playlistPreloadRunner())
            val work = WorkManager.getInstance(context)
            val request =
                OneTimeWorkRequestBuilder<PlaylistPreloadWorker>()
                    .setInputData(workDataOf("plugin" to "fixture.missing", "account" to "fixture", "audio" to arrayOf("fixture.missing")))
                    .build()
            work.enqueue(request)
            try {
                val result =
                    withTimeout(30_000) {
                        work.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished }
                    }
                assertEquals(WorkInfo.State.FAILED, result.state)
                assertNotNull(result.outputData.getString("error"))
            } finally {
                work.cancelWorkById(request.id)
            }
        }
}
