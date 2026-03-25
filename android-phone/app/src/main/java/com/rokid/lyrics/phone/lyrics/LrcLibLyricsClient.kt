package com.rokid.lyrics.phone.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rokid.lyrics.contracts.LyricsLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class LyricsLookupRequest(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int? = null,
)

data class LyricsFetchResult(
    val trackTitle: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Int?,
    val provider: String,
    val synced: Boolean,
    val lines: List<LyricsLine>,
    val plainLyrics: String,
    val sourceSummary: String,
)

class LrcLibLyricsClient(
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun fetch(request: LyricsLookupRequest): LyricsFetchResult = withContext(Dispatchers.IO) {
        val exact = fetchTrack("/api/get-cached", request)
            ?: fetchTrack("/api/get", request)
            ?: fetchSearchFallback(request)
            ?: error("No lyrics found on LRCLIB for ${request.title} by ${request.artist}.")
        parseTrack(exact, request)
    }

    private fun fetchTrack(path: String, request: LyricsLookupRequest): JsonObject? {
        val httpUrl = baseUrl(path, request)
        val httpRequest = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        }
    }

    private fun fetchSearchFallback(request: LyricsLookupRequest): JsonObject? {
        val urlBuilder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", request.title.trim())
            .addQueryParameter("artist_name", request.artist.trim())
        request.album.trim().takeIf { it.isNotEmpty() }?.let { urlBuilder.addQueryParameter("album_name", it) }
        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            val candidates = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: return null
            return pickSearchCandidate(candidates)
        }
    }

    private fun pickSearchCandidate(candidates: JsonArray): JsonObject? {
        val synced = candidates.mapNotNull { it.asJsonObjectOrNull() }
            .firstOrNull { candidate -> candidate.stringOrNull("syncedLyrics").isNullOrBlank().not() }
        return synced ?: candidates.firstOrNull()?.asJsonObjectOrNull()
    }

    private fun parseTrack(track: JsonObject, request: LyricsLookupRequest): LyricsFetchResult {
        val syncedLyrics = track.stringOrNull("syncedLyrics").orEmpty()
        val plainLyrics = track.stringOrNull("plainLyrics").orEmpty()
        val instrumental = track.booleanOrFalse("instrumental")
        val lines = parseSyncedLyrics(syncedLyrics)
        val trackTitle = track.stringOrNull("trackName") ?: request.title
        val artistName = track.stringOrNull("artistName") ?: request.artist
        val albumName = track.stringOrNull("albumName") ?: request.album
        val duration = track.intOrNull("duration") ?: request.durationSeconds
        val synced = lines.isNotEmpty()
        val summary = when {
            instrumental -> "Instrumental track reported by LRCLIB."
            synced -> "Synced lyrics loaded from LRCLIB with ${lines.size} timed lines."
            plainLyrics.isNotBlank() -> "Plain lyrics loaded from LRCLIB."
            else -> "Track resolved on LRCLIB, but no lyrics payload was returned."
        }
        return LyricsFetchResult(
            trackTitle = trackTitle,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = duration,
            provider = "LRCLIB",
            synced = synced,
            lines = lines,
            plainLyrics = plainLyrics,
            sourceSummary = summary,
        )
    }

    private fun parseSyncedLyrics(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line ->
                val match = TIMESTAMP_REGEX.matchEntire(line) ?: return@mapNotNull null
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val text = match.groupValues[3].trim().ifEmpty { "(instrumental)" }
                LyricsLine(
                    startTimeMs = minutes * 60_000L + (seconds * 1000).toLong(),
                    text = text,
                )
            }
            .toList()
    }

    private fun baseUrl(path: String, request: LyricsLookupRequest) =
        "https://lrclib.net$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("track_name", request.title.trim())
            addQueryParameter("artist_name", request.artist.trim())
            request.album.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("album_name", it) }
            request.durationSeconds?.takeIf { it > 0 }?.let { addQueryParameter("duration", it.toString()) }
        }.build()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private companion object {
        private val TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2}(?:\.\d+)?)\](.*)""")
        private const val USER_AGENT = "Rokid-Lyrics/0.1"
    }
}
