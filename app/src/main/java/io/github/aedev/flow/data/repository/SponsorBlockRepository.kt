package io.github.aedev.flow.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.network.AppProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SponsorBlockRepository
    @Inject
    constructor() {
        private val client: OkHttpClient
            get() = AppProxyManager.applyTo(OkHttpClient.Builder()).build()
        private val gson = Gson()
        private val segmentListType = object : TypeToken<List<SponsorBlockSegment>>() {}.type
        private val baseUrl = "https://sponsor.ajay.app/api/skipSegments"

        // Categories to fetch
        private val categories = listOf("sponsor", "intro", "outro", "selfpromo", "interaction", "music_offtopic")

        suspend fun getSegments(videoId: String): List<SponsorBlockSegment> =
            withContext(Dispatchers.IO) {
                try {
                    val categoriesJson = gson.toJson(categories)
                    val encodedCategories = URLEncoder.encode(categoriesJson, "UTF-8")
                    val url = "$baseUrl?videoID=$videoId&categories=$encodedCategories"

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .build()

                    val response = client.newCall(request).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val responseBody = resp.body?.string() ?: return@withContext emptyList()
                            return@withContext parseSegments(responseBody) ?: emptyList()
                        } else {
                            return@withContext emptyList()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext emptyList()
                }
            }

        /**
         * Read segments back from a stored payload. Null means "nothing stored or nothing readable",
         * which callers treat differently from an empty segment list.
         */
        fun parseSegments(json: String?): List<SponsorBlockSegment>? {
            if (json.isNullOrBlank()) return null
            return try {
                gson.fromJson(json, segmentListType)
            } catch (e: Exception) {
                null
            }
        }

        /** Serialize segments for the download store, in the shape [parseSegments] reads back. */
        fun serializeSegments(segments: List<SponsorBlockSegment>): String = gson.toJson(segments)
    }
