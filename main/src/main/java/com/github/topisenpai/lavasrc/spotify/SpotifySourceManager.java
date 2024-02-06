package com.github.topisenpai.lavasrc.spotify;

import com.github.topisenpai.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topisenpai.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topisenpai.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable {

    public static final Pattern URL_PATTERN = Pattern.compile("(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
    public static final String SEARCH_PREFIX = "spsearch:";
    public static final String RECOMMENDATIONS_PREFIX = "sprec:";
    public static final String SHARE_URL = "https://spotify.link";
    public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
    public static final int ALBUM_MAX_PAGE_ITEMS = 50;
    public static final String API_BASE = "https://api.spotify.com/v1/";
    private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

    private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    private final String countryCode;
    private int playlistPageLimit = 6;
    private int albumPageLimit = 6;
    private String token;
    private Instant tokenExpire;

    public SpotifySourceManager(String[] providers, String countryCode, AudioPlayerManager audioPlayerManager) {
        this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
    }

    public SpotifySourceManager(String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
        super(audioPlayerManager, mirroringAudioTrackResolver);

        if (countryCode == null || countryCode.isEmpty()) {
            countryCode = "US";
        }
        this.countryCode = countryCode;
    }

    public void setPlaylistPageLimit(int playlistPageLimit) {
        this.playlistPageLimit = playlistPageLimit;
    }

    public void setAlbumPageLimit(int albumPageLimit) {
        this.albumPageLimit = albumPageLimit;
    }

    @Override
    public String getSourceName() {
        return "spotify";
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new SpotifyAudioTrack(trackInfo,
                DataFormatTools.readNullableText(input),
                DataFormatTools.readNullableText(input),
                this
        );
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        try {
            if (reference.identifier.startsWith(SEARCH_PREFIX)) {
                return this.getSearch(reference.identifier.substring(SEARCH_PREFIX.length()).trim());
            }

            if (reference.identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
                return this.getRecommendations(reference.identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim());
            }

            // If the identifier is a share URL, we need to follow the redirect to find out the real url behind it
            if (reference.identifier.startsWith(SHARE_URL)) {
                var request = new HttpGet(reference.identifier);
                request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
                try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
                    if (response.getStatusLine().getStatusCode() == 307) {
                        var location = response.getFirstHeader("Location").getValue();
                        if (location.startsWith("https://open.spotify.com/")) {
                            return this.loadItem(manager, new AudioReference(location, reference.title));
                        }
                    }
                    return null;
                }
            }

            var matcher = URL_PATTERN.matcher(reference.identifier);
            if (!matcher.find()) {
                return null;
            }

            var id = matcher.group("identifier");
            switch (matcher.group("type")) {
                case "album":
                    return this.getAlbum(id);

                case "track":
                    return this.getTrack(id);

                case "playlist":
                    return this.getPlaylist(id);

                case "artist":
                    return this.getArtist(id);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void obtainAccessToken() throws IOException {
        var accessTokenUrl = "https://open.spotify.com/get_access_token?reason=transport&productType=embed";
        var request = new HttpGet(accessTokenUrl);
        var json = HttpClientTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);

        this.token = json.get("accessToken").text();
        var expirationTimestampMs = json.get("accessTokenExpirationTimestampMs").asLong(0);
        this.tokenExpire = Instant.ofEpochMilli(expirationTimestampMs);
    }

public String getToken() throws IOException {
    if (this.token == null || this.tokenExpire == null || this.tokenExpire.isBefore(Instant.now())) {
        this.obtainAccessToken();
    }
    return this.token;
}


    public JsonBrowser getJson(String uri) throws IOException {
        var request = new HttpGet(uri);
        request.addHeader("Authorization", "Bearer " + this.getToken());
        return HttpClientTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
    }

    public AudioItem getSearch(String query) throws IOException {
        var json = this.getJson(API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=track");
        if (json == null || json.get("tracks").get("items").values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Search results for: " + query, this.parseTrackItems(json.get("tracks")), null, true);
    }

    public AudioItem getRecommendations(String query) throws IOException {
        var json = this.getJson(API_BASE + "recommendations?" + query);
        if (json == null || json.get("tracks").values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist("Spotify Recommendations:", this.parseTracks(json), null, false);
    }

    public AudioItem getAlbum(String id) throws IOException {
        var json = this.getJson(API_BASE + "albums/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        var tracks = new ArrayList<AudioTrack>();
        JsonBrowser page;
        var offset = 0;
        var pages = 0;
        do {
            page = this.getJson(API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset);
            offset += ALBUM_MAX_PAGE_ITEMS;

            var tracksPage = this.getJson(API_BASE + "tracks/?ids=" + page.get("items").values().stream().map(track -> track.get("id").text()).collect(Collectors.joining(",")));

            tracks.addAll(this.parseTracks(tracksPage));
        } while (page.get("next").text() != null && ++pages < this.albumPageLimit);

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(json.get("name").text(), tracks, null, false);
    }

    public AudioItem getPlaylist(String id) throws IOException {
        var json = this.getJson(API_BASE + "playlists/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }

        var tracks = new ArrayList<AudioTrack>();
        JsonBrowser page;
        var offset = 0;
        var pages = 0;
        do {
            page = this.getJson(API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset);
            offset += PLAYLIST_MAX_PAGE_ITEMS;

            for (var value : page.get("items").values()) {
                var track = value.get("track");
                if (track.isNull() || track.get("is_local").asBoolean(false)) {
                    continue;
                }
                tracks.add(this.parseTrack(track));
            }

        } while (page.get("next").text() != null && ++pages < this.playlistPageLimit);

        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(json.get("name").text(), tracks, null, false);
    }

    public AudioItem getArtist(String id) throws IOException {
        var json = this.getJson(API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode);
        if (json == null || json.get("tracks").values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        return new BasicAudioPlaylist(json.get("tracks").index(0).get("artists").index(0).get("name").text() + "'s Top Tracks", this.parseTracks(json), null, false);
    }

    public AudioItem getTrack(String id) throws IOException {
        var json = this.getJson(API_BASE + "tracks/" + id);
        if (json == null) {
            return AudioReference.NO_TRACK;
        }
        return parseTrack(json);
    }

    private List<AudioTrack> parseTracks(JsonBrowser json) {
        var tracks = new ArrayList<AudioTrack>();
        for (var value : json.get("tracks").values()) {
            tracks.add(this.parseTrack(value));
        }
        return tracks;
    }

    private List<AudioTrack> parseTrackItems(JsonBrowser json) {
        var tracks = new ArrayList<AudioTrack>();
        for (var value : json.get("items").values()) {
            if (value.get("is_local").asBoolean(false)) {
                continue;
            }
            tracks.add(this.parseTrack(value));
        }
        return tracks;
    }

    private AudioTrack parseTrack(JsonBrowser json) {
        return new SpotifyAudioTrack(
                new AudioTrackInfo(
                        json.get("name").text(),
                        json.get("artists").index(0).get("name").text(),
                        json.get("duration_ms").asLong(0),
                        json.get("id").text(),
                        false,
                        json.get("external_urls").get("spotify").text()
                ),
                json.get("external_ids").get("isrc").text(),
                json.get("album").get("images").index(0).get("url").text(),
                this
        );
    }

    @Override
    public void shutdown() {
        try {
            this.httpInterfaceManager.close();
        } catch (IOException e) {
            log.error("Failed to close HTTP interface manager", e);
        }
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        this.httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        this.httpInterfaceManager.configureBuilder(configurator);
    }
}
