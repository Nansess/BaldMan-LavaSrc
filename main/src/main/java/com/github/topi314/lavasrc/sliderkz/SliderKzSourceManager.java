package com.github.topi314.lavasrc.sliderkz;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
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

public class SliderKzSourceManager
  implements AudioSourceManager, HttpConfigurable {

  public static final String SEARCH_PREFIX = "sksearch:";
  public static final String PUBLIC_API_BASE =
    "https://hayqbhgr.slider.kz/vk_auth.php?q=";

  private static final Logger log = LoggerFactory.getLogger(
    SliderKzSourceManager.class
  );

  private final HttpInterfaceManager httpInterfaceManager;

  public SliderKzSourceManager() {
    this.httpInterfaceManager =
      HttpClientTools.createDefaultThreadLocalManager();
  }

  @Override
  public String getSourceName() {
    return "sliderkz";
  }

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

  private AudioItem getSearchWithRetry(String query, int maxRetries)
    throws IOException {
    for (int retry = 0; retry <= maxRetries; retry++) {
      try {
        log.info(
          "Attempting search (Retry " +
          (retry + 1) +
          " of " +
          (maxRetries + 1) +
          "): " +
          query
        );
        var json =
          this.getJson(
              PUBLIC_API_BASE + URLEncoder.encode(query, StandardCharsets.UTF_8)
            );

        if (
          json.isNull() ||
          json.get("audios").isNull() ||
          json.get("audios").get("").isNull()
        ) {
          log.info("Search result is empty.");
          return AudioReference.NO_TRACK;
        }

        var tracks = parseTracks(json.get("audios").get(""));
        if (tracks.isEmpty()) {
          log.info("No tracks found in the search result.");
          return AudioReference.NO_TRACK;
        }

        log.info("Search successful. Found " + tracks.size() + " track(s).");
        return new BasicAudioPlaylist(
          "SliderKz Music Search: " + query,
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

  public JsonBrowser getJson(String uri) throws IOException {
    var request = new HttpGet(uri);
    request.setHeader("Accept", "application/json");
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
      return null;
    }

    try {
      var duration = Long.parseLong(rawDuration) * 1000;

      var titleArtist = audio.get("tit_art").text();
      var originalUrl = audio.get("url").text();
      String coverUri = null;

      String url = originalUrl.startsWith("https://") ||
        originalUrl.startsWith("http://")
        ? originalUrl
        : "https://hayqbhgr.slider.kz/" +
        originalUrl.replace(" ", "+").replace("<", "").replace(">", "");

      var audioTrack = new SliderKzAudioTrack(
        new AudioTrackInfo(
          extractTitle(titleArtist),
          extractArtist(titleArtist),
          duration,
          id,
          false,
          url,
          formatCoverUri(coverUri),
          null
        ),
        this
      );
      return audioTrack;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String extractTitle(String titleArtist) {
    String[] parts = titleArtist.split(" - ");
    return parts.length > 1 ? parts[1] : null;
  }

  private String extractArtist(String titleArtist) {
    String[] parts = titleArtist.split(" - ");
    return parts.length > 0 ? parts[0] : null;
  }

  private String formatCoverUri(String coverUri) {
    return coverUri != null
      ? "https://" + coverUri.replace("%%", "400x400")
      : null;
  }

  @Override
  public boolean isTrackEncodable(AudioTrack track) {
    return true;
  }

  @Override
  public void encodeTrack(AudioTrack track, DataOutput output) {}

  @Override
  public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
    return new SliderKzAudioTrack(trackInfo, this);
  }

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
