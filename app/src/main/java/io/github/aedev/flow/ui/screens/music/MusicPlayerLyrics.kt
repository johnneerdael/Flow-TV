package io.github.aedev.flow.ui.screens.music

import android.content.Context
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.lyrics.LyricsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The music player's lyrics: fetching for each track, refreshing, browsing other sources, manual
 * edits and timing. Everything it learns is written into the player's shared ui state.
 */
internal class MusicPlayerLyrics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MusicPlayerUiState>,
    private val lyricsHelper: LyricsHelper,
    private val playerPreferences: PlayerPreferences,
) {
    private var lyricsJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            playerPreferences.lyricsShowTranslation.collect { show ->
                uiState.update { it.copy(lyricsShowTranslation = show) }
            }
        }
        scope.launch {
            playerPreferences.lyricsShowRomanization.collect { show ->
                uiState.update { it.copy(lyricsShowRomanization = show) }
            }
        }
        scope.launch { playerPreferences.lyricsAutoRomanize.collect { on -> uiState.update { it.copy(lyricsAutoRomanize = on) } } }
    }

    fun setShowTranslation(show: Boolean) {
        scope.launch { playerPreferences.setLyricsShowTranslation(show) }
    }

    fun setShowRomanization(show: Boolean) {
        scope.launch { playerPreferences.setLyricsShowRomanization(show) }
    }

    fun setAutoRomanize(enabled: Boolean) {
        scope.launch { playerPreferences.setLyricsAutoRomanize(enabled) }
    }

    private fun cleanName(name: String): String =
        name
            .replace(Regex("(?i)\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(\\[]official (audio|video|music video|lyric video)[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(\\[]lyrics?[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(]feat\\.? .*?[)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[\\[]feat\\.? .*?[\\]]", RegexOption.IGNORE_CASE), "")
            .trim()

    fun fetch(
        videoId: String,
        artist: String,
        title: String,
        duration: Int? = null,
        album: String? = null,
    ) {
        lyricsJob?.cancel()
        lyricsJob =
            scope.launch {
                uiState.update {
                    it.copy(
                        isLyricsLoading = true,
                        lyrics = null,
                        syncedLyrics = emptyList(),
                        lyricsProviderName = "",
                        lyricsSyncOffsetMs = 0L,
                        lyricsCandidates = emptyList(),
                    )
                }

                val cleanArtist = cleanName(artist)
                val cleanTitle = cleanName(title)
                val targetDuration = duration ?: (uiState.value.duration.toInt() / 1000)

                try {
                    val result = lyricsHelper.getLyrics(videoId, cleanTitle, cleanArtist, targetDuration, album)

                    if (result != null) {
                        val (entries, providerName) = result
                        val hasWords = entries.any { it.words != null }
                        val isSynced = lyricsHelper.entriesAreSynced(entries)
                        android.util.Log.d(
                            "MusicPlayerViewModel",
                            "Got ${entries.size} lyrics lines from $providerName (word-sync=$hasWords, synced=$isSynced)",
                        )

                        val plainText = entries.joinToString("\n") { it.text }
                        uiState.update {
                            it.copy(
                                isLyricsLoading = false,
                                lyrics = plainText.takeIf { it.isNotBlank() },
                                syncedLyrics = if (isSynced) entries else emptyList(),
                                lyricsProviderName = providerName,
                            )
                        }
                    } else {
                        uiState.update { it.copy(isLyricsLoading = false) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayerViewModel", "Lyrics fetch failed", e)
                    uiState.update { it.copy(isLyricsLoading = false) }
                }
            }
    }

    fun refresh() {
        val track = uiState.value.currentTrack ?: return
        scope.launch {
            try {
                lyricsHelper.forceRefresh(track.videoId)
            } catch (e: Exception) {
                android.util.Log.w("MusicPlayerViewModel", "forceRefresh failed: ${e.message}")
            }
            fetch(
                videoId = track.videoId,
                artist = track.artist,
                title = track.title,
                duration = track.duration,
                album = track.album,
            )
        }
    }

    fun setTextAlign(align: String) {
        scope.launch { playerPreferences.setLyricsTextAlign(align) }
    }
}
