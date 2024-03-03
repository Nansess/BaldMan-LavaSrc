package com.github.topi314.lavasrc.tidal;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TidalSourceManager
  extends MirroringAudioSourceManager
  implements HttpConfigurable {

  public static final Pattern URL_PATTERN = Pattern.compile(
    "https?://(?:(?:listen|www)\\.)?tidal\\.com/(?:browse/)?(?<type>album|track|playlist)/(?<id>[a-zA-Z0-9\\-]+)"
  );

  public static final String SEARCH_PREFIX = "tdsearch:";
  public static final String PUBLIC_API_BASE = "https://api.tidal.com/v1/";
  public static final int PLAYLIST_MAX_PAGE_ITEMS = 750;
  public static final int ALBUM_MAX_PAGE_ITEMS = 120;

  private static final Logger log = LoggerFactory.getLogger(
    TidalSourceManager.class
  );

  private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
  private int searchLimit = 6;
  private final String countryCode;

  public TidalSourceManager(
    String[] providers,
    String countryCode,
    AudioPlayerManager audioPlayerManager
  ) {
    this(
      countryCode,
      audioPlayerManager,
      new DefaultMirroringAudioTrackResolver(providers)
    );
  }

  public TidalSourceManager(
    String[] providers,
    String countryCode,
    Function<Void, AudioPlayerManager> audioPlayerManager
  ) {
    this(
      countryCode,
      audioPlayerManager,
      new DefaultMirroringAudioTrackResolver(providers)
    );
  }

  public TidalSourceManager(
    String countryCode,
    AudioPlayerManager audioPlayerManager,
    MirroringAudioTrackResolver mirroringAudioTrackResolver
  ) {
    this(
      countryCode,
      unused -> audioPlayerManager,
      mirroringAudioTrackResolver
    );
  }

  public TidalSourceManager(
    String countryCode,
    Function<Void, AudioPlayerManager> audioPlayerManager,
    MirroringAudioTrackResolver mirroringAudioTrackResolver
  ) {
    super(audioPlayerManager, mirroringAudioTrackResolver);
    if (countryCode == null || countryCode.isEmpty()) {
      countryCode = "US";
    }
    this.countryCode = countryCode;
  }

  public void setSearchLimit(int searchLimit) {
    this.searchLimit = searchLimit;
  }

  @Override
  public String getSourceName() {
    return "tidal";
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input)
    throws IOException {
    var extendedAudioTrackInfo = super.decodeTrack(input);
    return new TidalAudioTrack(
      trackInfo,
      extendedAudioTrackInfo.albumName,
      extendedAudioTrackInfo.albumUrl,
      extendedAudioTrackInfo.artistUrl,
      extendedAudioTrackInfo.artistArtworkUrl,
      extendedAudioTrackInfo.previewUrl,
      extendedAudioTrackInfo.isPreview,
      this
    );
  }

  @Override
  public AudioItem loadItem(
    AudioPlayerManager manager,
    AudioReference reference
  ) {
    try {
      var identifier = reference.identifier;
      var matcher = URL_PATTERN.matcher(identifier);
      if (matcher.matches()) {
        String type = matcher.group("type");
        String id = matcher.group("id");

        switch (type) {
          case "album":
            return getAlbum(id);
          case "track":
            return getTrack(id);
          case "playlist":
            return getPlaylist(id);
          default:
            return null;
        }
      } else if (reference.identifier.startsWith(SEARCH_PREFIX)) {
        String query = reference.identifier.substring(SEARCH_PREFIX.length());
        if (query != null && !query.isEmpty()) {
          return getSearch(query);
        } else {
          return AudioReference.NO_TRACK;
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private AudioItem getSearchWithRetry(String query, int maxRetries)
    throws IOException {
    for (int retry = 0; retry <= maxRetries; retry++) {
      try {
        String apiUrl =
          PUBLIC_API_BASE +
          "search?query=" +
          URLEncoder.encode(query, StandardCharsets.UTF_8) +
          "&offset=0&limit=" +
          searchLimit +
          "&countryCode=" +
          countryCode;

        log.info(
          "Attempting search (Retry " +
          (retry + 1) +
          " of " +
          (maxRetries + 1) +
          "): " +
          query
        );

        var json = this.getJson(apiUrl);

        log.info("API Response:\n" + json.toString());

        if (
          json.isNull() ||
          json.get("tracks").isNull() ||
          json.get("tracks").get("items").isNull() ||
          json.get("tracks").get("items").text().isEmpty()
        ) {
          log.info("Search result is empty.");
          return AudioReference.NO_TRACK;
        }

        var tracks = parseTracks(json.get("tracks").get("items"));

        if (tracks.isEmpty()) {
          log.info("No tracks found in the search result.");
          return AudioReference.NO_TRACK;
        }

        log.info("Search successful. Found " + tracks.size() + " track(s).");
        return new BasicAudioPlaylist(
          "Tidal Music Search: " + query,
          tracks,
          null,
          true
        );
      } catch (SocketTimeoutException e) {
        log.info(
          "Retry " +
          (retry + 1) +
          " of " +
          (maxRetries + 1) +
          ": Socket timeout"
        );
        if (retry == maxRetries) {
          log.info("All retries failed. Giving up.");
          return AudioReference.NO_TRACK;
        }
      }
    }
    return AudioReference.NO_TRACK;
  }

  private AudioItem getSearch(String query) throws IOException {
    int maxRetries = 2;
    log.info("Initiating search: " + query);
    return getSearchWithRetry(query, maxRetries);
  }

public AudioItem getTrack(String trackId) throws IOException {
    try {
        String apiUrl = PUBLIC_API_BASE + "tracks/" + trackId + "?countryCode=" + countryCode;
        var json = getJson(apiUrl);

        if (json == null || json.isNull()) {
            log.info("Track not found for ID: {}", trackId);
            return AudioReference.NO_TRACK;
        }

        var track = parseTrack(json);

        if (track == null) {
            log.info("Failed to parse track for ID: {}", trackId);
            return AudioReference.NO_TRACK;
        }

        log.info("Track loaded successfully for ID: {}", trackId);
        return track;
    } catch (SocketTimeoutException e) {
        log.error("Socket timeout while fetching track with ID: {}", trackId, e);
        return AudioReference.NO_TRACK;
    }
}


private AudioItem getAlbum(String albumId) throws IOException {
    try {
        String apiUrl =
            PUBLIC_API_BASE +
            "albums/" +
            albumId +
            "/tracks?countryCode=" +
            countryCode +
            "&limit=" +
            ALBUM_MAX_PAGE_ITEMS;  
        var json = getJson(apiUrl);

      if (json == null || json.get("items").isNull()) {
        log.info("Tracks not found for Album ID: {}", albumId);
        return AudioReference.NO_TRACK;
      }

      var albumTracks = parseAlbum(json);
      if (albumTracks.isEmpty()) {
        log.info("No tracks found for Album ID: {}", albumId);
        return AudioReference.NO_TRACK;
      }

      var albumTitle = json
        .get("items")
        .index(0)
        .get("album")
        .get("title")
        .text();
      var artist = json
        .get("items")
        .index(0)
        .get("artists")
        .index(0)
        .get("name")
        .text();

      return new BasicAudioPlaylist(
        albumTitle,
        albumTracks,
        null,
        false 
      );
    } catch (SocketTimeoutException e) {
      log.error(
        "Socket timeout while fetching tracks for Album ID: {}",
        albumId,
        e
      );
      return AudioReference.NO_TRACK;
    }
  }

  private AudioItem getPlaylist(String playlistId) throws IOException {
    try {
      String playlistInfoUrl =
        PUBLIC_API_BASE +
        "playlists/" +
        playlistId +
        "?countryCode=" +
        countryCode;
      var playlistInfoJson = getJson(playlistInfoUrl);

      if (
        playlistInfoJson == null ||
        playlistInfoJson.get("numberOfTracks").isNull()
      ) {
        log.info("Playlist not found for ID: {}", playlistId);
        return AudioReference.NO_TRACK;
      }

      var playlistTitle = playlistInfoJson.get("title").text();

String playlistTracksUrl =
            PUBLIC_API_BASE +
            "playlists/" +
            playlistId +
            "/tracks?countryCode=" +
            countryCode +
            "&limit=" +
            PLAYLIST_MAX_PAGE_ITEMS;
      var playlistTracksJson = getJson(playlistTracksUrl);

      if (
        playlistTracksJson == null || playlistTracksJson.get("items").isNull()
      ) {
        log.info("Tracks not found for Playlist ID: {}", playlistId);
        return AudioReference.NO_TRACK;
      }

      var playlistTracks = parsePlaylist(playlistTracksJson);
      if (playlistTracks.isEmpty()) {
        log.info("No tracks found for Playlist ID: {}", playlistId);
        return AudioReference.NO_TRACK;
      }

      return new BasicAudioPlaylist(
        playlistTitle,
        playlistTracks,
        null, 
        false 
      );
    } catch (SocketTimeoutException e) {
      log.error(
        "Socket timeout while fetching playlist for ID: {}",
        playlistId,
        e
      );
      return AudioReference.NO_TRACK;
    }
  }

  public JsonBrowser getJson(String uri) throws IOException {
    var request = new HttpGet(uri);
    request.setHeader(
      "user-agent",
      "TIDAL/3704 CFNetwork/1220.1 Darwin/20.3.0"
    );
    request.setHeader("x-tidal-token", "i4ZDjcyhed7Mu47q");
    JsonBrowser json = LavaSrcTools.fetchResponseAsJson(
      httpInterfaceManager.getInterface(),
      request
    );
    return json;
  }

  private List<AudioTrack> parseTracks(JsonBrowser json) {
    var tracks = new ArrayList<AudioTrack>();
    for (var audio : json.values()) {
      var parsedTrack = parseTrack(audio);
      if (parsedTrack != null) {
        tracks.add(parsedTrack);
      }
    }
    return tracks;
  }

  private AudioTrack parseTrack(JsonBrowser audio) {
    var id = audio.get("id").text();
    var rawDuration = audio.get("duration").text();

    if (rawDuration == null) {
      log.warn(
        "Skipping track with null duration. Audio JSON: {}",
        audio.toString()
      );
      return null;
    }

    try {
      var duration = Long.parseLong(rawDuration) * 1000;

      var title = audio.get("title").text();
      var originalUrl = audio.get("url").text();
      var artistsArray = audio.get("artists");
      var artistName = "";

      for (int i = 0; i < artistsArray.values().size(); i++) {
        var currentArtistName = artistsArray.index(i).get("name").text();
        artistName += (i > 0 ? ", " : "") + currentArtistName;
      }
      var coverIdentifier = audio.get("album").get("cover").text();
      var isrc = audio.get("isrc").text();

      var formattedCoverIdentifier = coverIdentifier.replaceAll("-", "/");

      var artworkUrl =
        "https://resources.tidal.com/images/" +
        formattedCoverIdentifier +
        "/1280x1280.jpg";

      log.info("Audio JSON: {}", audio);

      log.info(
        "Parsed track: Title={}, Artist={}, Duration={}, OriginalUrl={}, ArtWorkURL={}, ID={}, ISRC={}",
        title,
        artistName,
        duration,
        originalUrl,
        artworkUrl,
        id,
        isrc
      );

      var audioTrack = new TidalAudioTrack(
        new AudioTrackInfo(
          title,
          artistName,
          duration,
          id,
          false,
          originalUrl,
          artworkUrl,
          isrc
        ),
        this
      );
      log.info("Created AudioTrack: {}", audioTrack);
      return audioTrack;
    } catch (NumberFormatException e) {
      log.error(
        "Error parsing duration for track. Audio JSON: {}",
        audio.toString(),
        e
      );
      return null;
    }
  }

  private List<AudioTrack> parseAlbum(JsonBrowser album) {
    var tracks = new ArrayList<AudioTrack>();
    var items = album.get("items");

    for (var item : items.values()) {
      var id = item.get("id").text();
      var rawDuration = item.get("duration").text();

      if (rawDuration == null) {
        log.warn(
          "Skipping track with null duration. Track JSON: {}",
          item.toString()
        );
        continue;
      }

      try {
        var duration = Long.parseLong(rawDuration) * 1000;

        var title = item.get("title").text();
        var originalUrl = item.get("url").text();
        var artistsArray = item.get("artists");
        var artistName = "";

        for (int i = 0; i < artistsArray.values().size(); i++) {
          var currentArtistName = artistsArray.index(i).get("name").text();
          artistName += (i > 0 ? ", " : "") + currentArtistName;
        }

        var coverIdentifier = item.get("album").get("cover").text();
        var isrc = item.get("isrc").text();

        var formattedCoverIdentifier = coverIdentifier.replaceAll("-", "/");

        var artworkUrl =
          "https://resources.tidal.com/images/" +
          formattedCoverIdentifier +
          "/1280x1280.jpg";

        log.info("Track JSON: {}", item);

        log.info(
          "Parsed track: Title={}, Artist={}, Duration={}, OriginalUrl={}, ArtWorkURL={}, ID={}, ISRC={}",
          title,
          artistName,
          duration,
          originalUrl,
          artworkUrl,
          id,
          isrc
        );

        var audioTrack = new TidalAudioTrack(
          new AudioTrackInfo(
            title,
            artistName,
            duration,
            id,
            false,
            originalUrl,
            artworkUrl,
            isrc
          ),
          this
        );

        log.info("Created AudioTrack: {}", audioTrack);
        tracks.add(audioTrack);
      } catch (NumberFormatException e) {
        log.error(
          "Error parsing duration for track. Track JSON: {}",
          item.toString(),
          e
        );
      }
    }

    return tracks;
  }

  private List<AudioTrack> parsePlaylist(JsonBrowser playlist) {
    var tracks = new ArrayList<AudioTrack>();
    var items = playlist.get("items");

    for (var item : items.values()) {
      var id = item.get("id").text();
      var rawDuration = item.get("duration").text();

      if (rawDuration == null) {
        log.warn(
          "Skipping track with null duration. Track JSON: {}",
          item.toString()
        );
        continue;
      }

      try {
        var duration = Long.parseLong(rawDuration) * 1000;

        var title = item.get("title").text();
        var originalUrl = item.get("url").text();
        var artistsArray = item.get("artists");
        var artistName = "";

        for (int i = 0; i < artistsArray.values().size(); i++) {
          var currentArtistName = artistsArray.index(i).get("name").text();
          artistName += (i > 0 ? ", " : "") + currentArtistName;
        }

        var coverIdentifier = item.get("album").get("cover").text();
        var isrc = item.get("isrc").text();

        var formattedCoverIdentifier = coverIdentifier.replaceAll("-", "/");

        var artworkUrl =
          "https://resources.tidal.com/images/" +
          formattedCoverIdentifier +
          "/1280x1280.jpg";

        log.info("Track JSON: {}", item);

        log.info(
          "Parsed track: Title={}, Artist={}, Duration={}, OriginalUrl={}, ArtWorkURL={}, ID={}, ISRC={}",
          title,
          artistName,
          duration,
          originalUrl,
          artworkUrl,
          id,
          isrc
        );

        var audioTrack = new TidalAudioTrack(
          new AudioTrackInfo(
            title,
            artistName,
            duration,
            id,
            false,
            originalUrl,
            artworkUrl,
            isrc
          ),
          this
        );

        log.info("Created AudioTrack: {}", audioTrack);
        tracks.add(audioTrack);
      } catch (NumberFormatException e) {
        log.error(
          "Error parsing duration for track. Track JSON: {}",
          item.toString(),
          e
        );
      }
    }

    return tracks;
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) {}

  @Override
  public void configureRequests(
    Function<RequestConfig, RequestConfig> configurator
  ) {
    httpInterfaceManager.configureRequests(configurator);
  }

  @Override
  public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
    httpInterfaceManager.configureBuilder(configurator);
  }

  @Override
  public void shutdown() {
    try {
      httpInterfaceManager.close();
    } catch (IOException e) {
      log.error("Failed to close HTTP interface manager", e);
    }
  }

  public HttpInterface getHttpInterface() {
    return httpInterfaceManager.getInterface();
  }
}
