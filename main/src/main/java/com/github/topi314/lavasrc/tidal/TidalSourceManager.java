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
  private static final String USER_AGENT =
    "TIDAL/3704 CFNetwork/1220.1 Darwin/20.3.0";
  private static final String TIDAL_TOKEN = "i4ZDjcyhed7Mu47q";

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
            return getAlbumOrPlaylist(id, "album", ALBUM_MAX_PAGE_ITEMS);
          case "track":
            return getTrack(id);
          case "playlist":
            return getAlbumOrPlaylist(id, "playlist", PLAYLIST_MAX_PAGE_ITEMS);
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

  private JsonBrowser getApiResponse(String apiUrl) throws IOException {
    var request = new HttpGet(apiUrl);
    request.setHeader("user-agent", USER_AGENT);
    request.setHeader("x-tidal-token", TIDAL_TOKEN);
    return LavaSrcTools.fetchResponseAsJson(
      httpInterfaceManager.getInterface(),
      request
    );
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
        var json = getApiResponse(apiUrl);

        if (
          json.isNull() ||
          json.get("tracks").isNull() ||
          json.get("tracks").get("items").isNull() ||
          json.get("tracks").get("items").text().isEmpty()
        ) {
          return AudioReference.NO_TRACK;
        }

        var tracks = parseTracks(json.get("tracks").get("items"));

        if (tracks.isEmpty()) {
          return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(
          "Tidal Music Search: " + query,
          tracks,
          null,
          true
        );
      } catch (SocketTimeoutException e) {
        if (retry == maxRetries) {
          return AudioReference.NO_TRACK;
        }
      }
    }
    return AudioReference.NO_TRACK;
  }

  private AudioItem getSearch(String query) throws IOException {
    int maxRetries = 2;
    return getSearchWithRetry(query, maxRetries);
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
      return new TidalAudioTrack(
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
    } catch (NumberFormatException e) {
      log.error(
        "Error parsing duration for track. Audio JSON: {}",
        audio.toString(),
        e
      );
      return null;
    }
  }

  private AudioItem getAlbumOrPlaylist(
    String itemId,
    String type,
    int maxPageItems
  ) throws IOException {
    try {
      // Fetch tracks directly for albums
      String apiUrl =
        PUBLIC_API_BASE +
        type +
        "s/" +
        itemId +
        "/tracks?countryCode=" +
        countryCode +
        "&limit=" +
        maxPageItems;
      var json = getApiResponse(apiUrl);

      if (json == null || json.get("items").isNull()) {
        return AudioReference.NO_TRACK;
      }

      var items = parseTrackItem(json);

      if (items.isEmpty()) {
        return AudioReference.NO_TRACK;
      }

      // Use playlist title for playlists and album title for albums
      String itemTitle = "";
      if (type.equalsIgnoreCase("playlist")) {
        // Fetch playlist information
        String playlistInfoUrl =
          PUBLIC_API_BASE +
          "playlists/" +
          itemId +
          "?countryCode=" +
          countryCode;
        var playlistInfoJson = getApiResponse(playlistInfoUrl);

        if (
          playlistInfoJson != null && !playlistInfoJson.get("title").isNull()
        ) {
          itemTitle = playlistInfoJson.get("title").text();
        }
      } else if (type.equalsIgnoreCase("album")) {
        // Fetch album information
        String albumInfoUrl =
          PUBLIC_API_BASE + "albums/" + itemId + "?countryCode=" + countryCode;
        var albumInfoJson = getApiResponse(albumInfoUrl);

        if (albumInfoJson != null && !albumInfoJson.get("title").isNull()) {
          itemTitle = albumInfoJson.get("title").text();
        }
      }

      return new BasicAudioPlaylist(itemTitle, items, null, false);
    } catch (SocketTimeoutException e) {
      log.error(
        "Socket timeout while fetching tracks for {} ID: {}",
        type,
        itemId,
        e
      );
      return AudioReference.NO_TRACK;
    }
  }

  public AudioItem getTrack(String trackId) throws IOException {
    try {
      String apiUrl =
        PUBLIC_API_BASE + "tracks/" + trackId + "?countryCode=" + countryCode;
      var json = getApiResponse(apiUrl);

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

  private List<AudioTrack> parseSingleTrack(JsonBrowser audio) {
    var tracks = new ArrayList<AudioTrack>();
    var items = audio.get("items");

    for (var audioItem : items.values()) {
      var parsedTrack = parseItem(audioItem);
      if (parsedTrack != null) {
        tracks.add(parsedTrack);
      }
    }
    return tracks;
  }

  private List<AudioTrack> parseTrackItem(JsonBrowser json) {
    var tracks = new ArrayList<AudioTrack>();
    var items = json.get("items");

    for (var audio : items.values()) {
      var parsedTrack = parseItem(audio);
      if (parsedTrack != null) {
        tracks.add(parsedTrack);
      }
    }
    return tracks;
  }

  private AudioTrack parseItem(JsonBrowser audio) {
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
      return new TidalAudioTrack(
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
    } catch (NumberFormatException e) {
      log.error(
        "Error parsing duration for track. Audio JSON: {}",
        audio.toString(),
        e
      );
      return null;
    }
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
