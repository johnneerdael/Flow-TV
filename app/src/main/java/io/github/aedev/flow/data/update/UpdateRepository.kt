package io.github.aedev.flow.data.update

import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.LocalDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where an automatic check may surface a release; each announces a version once. */
enum class UpdateAnnouncement { LAUNCH_PAGE, NOTIFICATION }

/** How the automatic checks space themselves out and which releases they may show. */
internal object UpdateSchedule {
    val COOLDOWN_MS: Long = TimeUnit.HOURS.toMillis(12)

    fun isDue(
        lastCheckMs: Long,
        nowMs: Long,
    ): Boolean = nowMs < lastCheckMs || nowMs - lastCheckMs >= COOLDOWN_MS

    fun mayAnnounce(
        version: String,
        skippedVersion: String?,
        announcedVersion: String?,
    ): Boolean = version != skippedVersion && version != announcedVersion
}

/**
 * The one place Milkbeat asks GitHub about releases. The launch check, the background worker and the
 * Settings button all come through here, so a cold start never fetches twice and a skipped version
 * stays skipped everywhere.
 */
@Singleton
class UpdateRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val dataManager: LocalDataManager,
    ) {
        private val mutex = Mutex()
        private val _latest = MutableStateFlow<AppRelease?>(null)

        /** The newest release found this process, or null when none is known or this build is current. */
        val latest: StateFlow<AppRelease?> = _latest.asStateFlow()

        /** Asks GitHub now. Throws when the request fails, so a check the user started can say so. */
        suspend fun fetch(): AppRelease? = mutex.withLock { fetchLocked() }

        /**
         * For the automatic checks: fetches only once the cooldown has passed, and returns a release
         * only if [announcement] has not shown this version before and the user has not skipped it.
         */
        suspend fun releaseToAnnounce(
            announcement: UpdateAnnouncement,
            nowMs: Long = System.currentTimeMillis(),
        ): AppRelease? =
            mutex.withLock {
                if (!UpdateSchedule.isDue(dataManager.lastUpdateCheck.first(), nowMs)) return@withLock null
                val release = runCatching { fetchLocked() }.getOrNull() ?: return@withLock null
                val announced =
                    when (announcement) {
                        UpdateAnnouncement.LAUNCH_PAGE -> dataManager.promptedUpdateVersion.first()
                        UpdateAnnouncement.NOTIFICATION -> dataManager.notifiedUpdateVersion.first()
                    }
                if (!UpdateSchedule.mayAnnounce(release.version, dataManager.skippedUpdateVersion.first(), announced)) {
                    return@withLock null
                }
                when (announcement) {
                    UpdateAnnouncement.LAUNCH_PAGE -> dataManager.setPromptedUpdateVersion(release.version)
                    UpdateAnnouncement.NOTIFICATION -> dataManager.setNotifiedUpdateVersion(release.version)
                }
                release
            }

        suspend fun skip(version: String) = dataManager.setSkippedUpdateVersion(version)

        private suspend fun fetchLocked(): AppRelease? {
            val release =
                withContext(Dispatchers.IO) {
                    val tag = latestTag()
                    if (!AppVersions.isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
                    val checksums = get(releaseAssetUrl(tag, RELEASE_CHECKSUMS))
                    val sha256 = releaseChecksum(checksums, RELEASE_APK) ?: throw IOException("No checksum for $RELEASE_APK in $tag")
                    releaseIfNewer(tag, BuildConfig.VERSION_NAME, sha256)
                }
            dataManager.setLastUpdateCheck(System.currentTimeMillis())
            _latest.value = release
            return release
        }

        /** The newest tag, read from where the stable APK link redirects; nothing is downloaded. */
        private fun latestTag(): String {
            val request =
                Request
                    .Builder()
                    .url(LATEST_RELEASE_APK_URL)
                    .head()
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
            return client.newBuilder().followRedirects(false).build().newCall(request).execute().use { response ->
                releaseTagFromRedirect(response.header("Location"))
                    ?: throw IOException("Update check failed: HTTP ${response.code} without a release redirect")
            }
        }

        private fun get(url: String): String {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
                response.body.string()
            }
        }
    }
