package com.github.topi314.lavasrc.tidal

import com.github.topi314.lavasrc.LavaSrcTools
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager
import com.sedmelluq.discord.lavaplayer.track.*
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.Pattern

class TidalSourceManager(
    providers: Array<String>,
    private var countryCode: String?,
    audioPlayerManager: Function<Void, AudioPlayerManager>
) : MirroringAudioSourceManager(audioPlayerManager, DefaultMirroringAudioTrackResolver(providers)),
    HttpConfigurable {

    companion object {
        val URL_PATTERN: Pattern = Pattern.compile(
            "https?://(?:(?:listen|www)\\.)?tidal\\.com/(?:browse/)?(?<type>album|track|playlist)/(?<id>[a-zA-Z0-9\\-]+)"
        )

        const val SEARCH_PREFIX = "tdsearch:"
        const val PUBLIC_API_BASE = "https://api.tidal.com/v1/"
        const val PLAYLIST_MAX_PAGE_ITEMS = 750
        const val ALBUM_MAX_PAGE_ITEMS = 120
        private const val USER_AGENT =
            "TIDAL/3704 CFNetwork/1220.1 Darwin/20.3.0"
        private const val TIDAL_TOKEN = "i4ZDjcyhed7Mu47q"
    }

    private val log: Logger = LoggerFactory.getLogger(TidalSourceManager::class.java)
    private val httpInterfaceManager: HttpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
    private var searchLimit = 7

    init {
        if (countryCode.isNullOrBlank()) {
            countryCode = "US"
        }
    }

    fun setSearchLimit(searchLimit: Int) {
        this.searchLimit = searchLimit
    }

    override fun getSourceName(): String {
        return "tidal"
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        val extendedAudioTrackInfo = super.decodeTrack(input)
        return TidalAudioTrack(
            trackInfo,
            extendedAudioTrackInfo.albumName,
            extendedAudioTrackInfo.albumUrl,
            extendedAudioTrackInfo.artistUrl,
            extendedAudioTrackInfo.artistArtworkUrl,
            extendedAudioTrackInfo.previewUrl,
            extendedAudioTrackInfo.isPreview,
            this
        )
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem {
        try {
            val identifier = reference.identifier
            val matcher = URL_PATTERN.matcher(identifier)
            if (matcher.matches()) {
                val type = matcher.group("type")
                val id = matcher.group("id")

                when (type) {
                    "album" -> return getAlbumOrPlaylist(id, "album", ALBUM_MAX_PAGE_ITEMS)
                    "track" -> return getTrack(id)
                    "playlist" -> return getAlbumOrPlaylist(id, "playlist", PLAYLIST_MAX_PAGE_ITEMS)
                    else -> return AudioReference.NO_TRACK
                }
            } else if (reference.identifier.startsWith(SEARCH_PREFIX)) {
                val query = reference.identifier.substring(SEARCH_PREFIX.length)
                return if (!query.isEmpty()) {
                    getSearch(query)
                } else {
                    AudioReference.NO_TRACK
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        return AudioReference.NO_TRACK
    }

    private fun getApiResponse(apiUrl: String): JsonBrowser {
        val request = HttpGet(apiUrl)
        request.setHeader("user-agent", USER_AGENT)
        request.setHeader("x-tidal-token", TIDAL_TOKEN)
        return LavaSrcTools.fetchResponseAsJson(httpInterfaceManager.getInterface(), request)!!
    }

    private fun parseTracks(json: JsonBrowser): List<AudioTrack> {
        val tracks: MutableList<AudioTrack> = ArrayList()
        for (audio in json.values()) {
            val parsedTrack = parseTrack(audio)
            if (parsedTrack != null) {
                tracks.add(parsedTrack)
            }
        }
        return tracks
    }

    private fun getSearchWithRetry(query: String, maxRetries: Int): AudioItem {
        for (retry in 0..maxRetries) {
            try {
                val apiUrl =
                    PUBLIC_API_BASE +
                            "search?query=" +
                            URLEncoder.encode(query, StandardCharsets.UTF_8) +
                            "&offset=0&limit=" +
                            searchLimit +
                            "&countryCode=" +
                            countryCode
                val json = getApiResponse(apiUrl)

                if (
                    json.isNull ||
                    json["tracks"].isNull ||
                    json["tracks"]["items"].isNull ||
                    json["tracks"]["items"].text().isEmpty()
                ) {
                    return AudioReference.NO_TRACK
                }

                val tracks = parseTracks(json["tracks"]["items"])

                if (tracks.isEmpty()) {
                    return AudioReference.NO_TRACK
                }

                return BasicAudioPlaylist(
                    "Tidal Music Search: $query",
                    tracks,
                    null,
                    true
                )
            } catch (e: SocketTimeoutException) {
                if (retry == maxRetries) {
                    return AudioReference.NO_TRACK
                }
            }
        }
        return AudioReference.NO_TRACK
    }

    private fun getSearch(query: String): AudioItem {
        val maxRetries = 2
        return getSearchWithRetry(query, maxRetries)
    }

    private fun parseTrack(audio: JsonBrowser): AudioTrack? {
        return newMethod(audio)
    }

    private fun newMethod(audio: JsonBrowser): AudioTrack? {
        val id = audio["id"].text()
        val rawDuration = audio["duration"].text()

        if (rawDuration == null) {
            log.warn("Skipping track with null duration. Audio JSON: {}", audio)
            return null
        }

        try {
            val duration = java.lang.Long.parseLong(rawDuration) * 1000

            val title = audio["title"].text()
            val originalUrl = audio["url"].text()
            val artistsArray = audio["artists"]
            val artistName = StringBuilder()
            for (i in 0 until artistsArray.values().size) {
                val currentArtistName = artistsArray.index(i)["name"].text()
                artistName.append(if (i > 0) ", " else "").append(currentArtistName)
            }
            val coverIdentifier = audio["album"]["cover"].text()
            val isrc = audio["isrc"].text()

            val formattedCoverIdentifier = coverIdentifier.replace("-", "/")

            val artworkUrl =
                "https://resources.tidal.com/images/$formattedCoverIdentifier/1280x1280.jpg"
            return TidalAudioTrack(
                AudioTrackInfo(
                    title,
                    artistName.toString(),
                    duration,
                    id,
                    false,
                    originalUrl,
                    artworkUrl,
                    isrc
                ),
                this
            )
        } catch (e: NumberFormatException) {
            log.error("Error parsing duration for track. Audio JSON: {}", audio, e)
            return null
        }
    }

    private fun getAlbumOrPlaylist(itemId: String, type: String, maxPageItems: Int): AudioItem {
        try {
            val apiUrl =
                PUBLIC_API_BASE +
                        type +
                        "s/" +
                        itemId +
                        "/tracks?countryCode=" +
                        countryCode +
                        "&limit=" +
                        maxPageItems
            val json = getApiResponse(apiUrl)

            if (json == null || json["items"].isNull) {
                return AudioReference.NO_TRACK
            }

            val items = parseTrackItem(json)

            if (items.isEmpty()) {
                return AudioReference.NO_TRACK
            }

            var itemTitle = ""
            if (type.equals("playlist", ignoreCase = true)) {
                val playlistInfoUrl =
                    PUBLIC_API_BASE +
                            "playlists/" +
                            itemId +
                            "?countryCode=" +
                            countryCode
                val playlistInfoJson = getApiResponse(playlistInfoUrl)

                if (
                    playlistInfoJson != null && !playlistInfoJson["title"].isNull
                ) {
                    itemTitle = playlistInfoJson["title"].text()
                }
            } else if (type.equals("album", ignoreCase = true)) {
                val albumInfoUrl =
                    PUBLIC_API_BASE + "albums/" + itemId + "?countryCode=" + countryCode
                val albumInfoJson = getApiResponse(albumInfoUrl)

                if (albumInfoJson != null && !albumInfoJson["title"].isNull) {
                    itemTitle = albumInfoJson["title"].text()
                }
            }

            return BasicAudioPlaylist(itemTitle, items, null, false)
        } catch (e: SocketTimeoutException) {
            log.error("Socket timeout while fetching tracks for {} ID: {}", type, itemId, e)
            return AudioReference.NO_TRACK
        }
    }

    @Throws(IOException::class)
    fun getTrack(trackId: String): AudioItem {
        try {
            val apiUrl =
                PUBLIC_API_BASE + "tracks/" + trackId + "?countryCode=" + countryCode
            val json = getApiResponse(apiUrl)

            if (json == null || json.isNull) {
                log.info("Track not found for ID: {}", trackId)
                return AudioReference.NO_TRACK
            }

            val track = parseTrack(json)

            if (track == null) {
                log.info("Failed to parse track for ID: {}", trackId)
                return AudioReference.NO_TRACK
            }

            log.info("Track loaded successfully for ID: {}", trackId)
            return track
        } catch (e: SocketTimeoutException) {
            log.error("Socket timeout while fetching track with ID: {}", trackId, e)
            return AudioReference.NO_TRACK
        }
    }

    private fun parseTrackItem(json: JsonBrowser): List<AudioTrack> {
        val tracks: MutableList<AudioTrack> = ArrayList()
        val items = json["items"]

        for (audio in items.values()) {
            val parsedTrack = parseItem(audio)
            if (parsedTrack != null) {
                tracks.add(parsedTrack)
            }
        }
        return tracks
    }

    private fun parseItem(audio: JsonBrowser): AudioTrack {
        return newMethod(audio)!!
    }

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
    }

    override fun configureRequests(configurator: Function<RequestConfig, RequestConfig>) {
        httpInterfaceManager.configureRequests(configurator)
    }

    override fun configureBuilder(configurator: Consumer<HttpClientBuilder>) {
        httpInterfaceManager.configureBuilder(configurator)
    }

    override fun shutdown() {
        try {
            httpInterfaceManager.close()
        } catch (e: IOException) {
            log.error("Failed to close HTTP interface manager", e)
        }
    }

    override fun getHttpInterface(): HttpInterface {
        return httpInterfaceManager.getInterface()
    }
}
