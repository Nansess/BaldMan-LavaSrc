package com.github.topi314.lavasrc.pandora;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PandoraSourceManager
  extends MirroringAudioSourceManager
  implements HttpConfigurable {

  public static final Pattern URL_PATTERN = Pattern.compile(
    "https://www\\.pandora\\.com/(playlist|station|podcast|artist)/.+"
  );
  public static final String SEARCH_PREFIX = "pdsearch:";
  public static final String PUBLIC_API_BASE = "https://www.pandora.com/api/";

  private static final Logger log = LoggerFactory.getLogger(
    PandoraSourceManager.class
  );

  private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
  private final String countryCode;

  public PandoraSourceManager(
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

  public PandoraSourceManager(
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

  @Override
  public String getSourceName() {
    return "pandora";
  }

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input)
    throws IOException {
    var extendedAudioTrackInfo = super.decodeTrack(input);
    return new PandoraAudioTrack(
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

  private String csrfToken;
  private String authToken;

  @Override
  public AudioItem loadItem(
    AudioPlayerManager manager,
    AudioReference reference
  ) {
    try {
      if (reference.identifier.startsWith(SEARCH_PREFIX)) {
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

private List<AudioTrack> parseTracks(JsonBrowser json) {
    var tracks = new ArrayList<AudioTrack>();
    for (var audio : json.values()) {
        if ("TR".equals(audio.get("type").text())) {
            var parsedTrack = parseTrack(audio);
            if (parsedTrack != null) {
                tracks.add(parsedTrack);
            }
        }
    }
    return tracks;
}


  private AudioItem getSearchWithRetry(String query, int maxRetries)
    throws IOException {
    for (int retry = 0; retry <= maxRetries; retry++) {
      try {
        String apiUrl = PUBLIC_API_BASE + "v3/sod/search";

        String jsonData =
          "{" +
          "\"annotate\": true," +
          "\"annotationRecipe\": \"CLASS_OF_2019\"," +
          "\"count\": 10," +
          "\"query\": \"" +
          URLEncoder.encode(query, StandardCharsets.UTF_8) +
          "\"," +
          "\"searchTime\": 0," +
          "\"start\": 0," +
          "\"listener\": null," +
          "\"types\": [\"TR\"]" +
          "}";

        var json = getApiResponse(apiUrl, jsonData);

        if (json == null || json.get("annotations").isNull()) {
          log.info("Search result is empty");
          return AudioReference.NO_TRACK;
        }

        var tracks = parseTracks(json.get("annotations"));

        if (tracks.isEmpty()) {
          return AudioReference.NO_TRACK;
        }

        return new BasicAudioPlaylist(
          "PanDora Music Search: " + query,
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
    return newMethod(audio);
  }

  private JsonBrowser getApiResponse(String apiUrl, String jsonData)
    throws IOException {
    var request = new HttpPost(apiUrl);
    request.setEntity(new StringEntity(jsonData, ContentType.APPLICATION_JSON));

    var json = LavaSrcTools.fetchResponseAsJson(
      httpInterfaceManager.getInterface(),
      request
    );

    log.info("Search API JSON response: {}", json);

    return json;
  }

  private AudioTrack newMethod(JsonBrowser audio) {
    var pandoraId = audio.get("pandoraId").text();
    var rawDuration = audio.get("durationMillis").text();

    // Set default values for various fields if they are null
    long duration = (rawDuration != null) ? Long.parseLong(rawDuration) : 0;
    String title = (audio.get("sortableName") != null) ? audio.get("sortableName").text() : "Unknown Title";
    String originalUrl = (audio.get("shareableUrlPath") != null) ? audio.get("shareableUrlPath").text() : "https://www.pandora.com";
    if (originalUrl != null && !originalUrl.startsWith("http")) {
        originalUrl = "https://www.pandora.com" + originalUrl;
    }
    String artistName = (audio.get("artistName") != null) ? audio.get("artistName").text() : "Unknown Artist";
    String isrc = (audio.get("isrc") != null) ? audio.get("isrc").text() : "Unknown ISRC";
    String artUrlPath = (audio.get("icon") != null && audio.get("icon").get("artUrl") != null) ? audio.get("icon").get("artUrl").text() : "/default-art-url";
    String artworkUrl = "https://content-images.p-cdn.com/" + artUrlPath;

    return new PandoraAudioTrack(
        new AudioTrackInfo(
            title,
            artistName,
            duration,
            pandoraId,
            false,
            originalUrl,
            artworkUrl,
            isrc
        ),
        this
    );
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
    return newMethod(audio);
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
