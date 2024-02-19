package com.github.topi314.lavasrc.deezer;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.flac.FlacAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

public class DeezerAudioTrack extends ExtendedAudioTrack {

  private final DeezerAudioSourceManager sourceManager;

  public DeezerAudioTrack(
    AudioTrackInfo trackInfo,
    DeezerAudioSourceManager sourceManager
  ) {
    this(trackInfo, null, null, null, null, null, false, sourceManager);
  }

  public DeezerAudioTrack(
    AudioTrackInfo trackInfo,
    String albumName,
    String albumUrl,
    String artistUrl,
    String artistArtworkUrl,
    String previewUrl,
    boolean isPreview,
    DeezerAudioSourceManager sourceManager
  ) {
    super(
      trackInfo,
      albumName,
      albumUrl,
      artistUrl,
      artistArtworkUrl,
      previewUrl,
      isPreview
    );
    this.sourceManager = sourceManager;
  }

   private URI getTrackMediaURI() throws IOException, URISyntaxException {
    var getMediaURL = new HttpGet(
      "https://api.nansess.com/getMediaURL" +
      "?trackIdentifier=" +
      this.trackInfo.identifier +
      "&format=" +
      this.sourceManager.getFormat()
    );

    var json = LavaSrcTools.fetchResponseAsJson(
      this.sourceManager.getHttpInterface(),
      getMediaURL
    );

    this.checkResponse(json, "Failed to get media URL: ");

    String mediaUrl = json.get("mediaURL").text();
    return new URI(mediaUrl);
  }

  private void checkResponse(JsonBrowser json, String message)
    throws IllegalStateException {
    if (json == null) {
      throw new IllegalStateException(message + "No response");
    }
    var errors = json.get("data").index(0).get("errors").values();
    if (!errors.isEmpty()) {
      var errorsStr = errors
        .stream()
        .map(error ->
          error.get("code").text() + ": " + error.get("message").text()
        )
        .collect(Collectors.joining(", "));
      throw new IllegalStateException(message + errorsStr);
    }
  }

  private byte[] getTrackDecryptionKey() throws NoSuchAlgorithmException {
    var md5 = Hex.encodeHex(
      MessageDigest
        .getInstance("MD5")
        .digest(this.trackInfo.identifier.getBytes()),
      true
    );
    var master_key = this.sourceManager.getMasterDecryptionKey().getBytes();

    var key = new byte[16];
    for (int i = 0; i < 16; i++) {
      key[i] = (byte) (md5[i] ^ md5[i + 16] ^ master_key[i]);
    }
    return key;
  }

@Override
  public void process(LocalAudioTrackExecutor executor) throws Exception {
    String trackFormat = this.sourceManager.getFormat();

    try (var httpInterface = this.sourceManager.getHttpInterface()) {
      if (!trackFormat.equalsIgnoreCase("FLAC")) {
        if (this.isPreview) {
          if (this.previewUrl == null) {
            throw new FriendlyException(
              "No preview url found",
              FriendlyException.Severity.COMMON,
              new IllegalArgumentException()
            );
          }
          try (
            var stream = new PersistentHttpStream(
              httpInterface,
              new URI(this.previewUrl),
              this.trackInfo.length
            )
          ) {
            processDelegate(
              new Mp3AudioTrack(this.trackInfo, stream),
              executor
            );
          }
        } else {
          try (
            var stream = new DeezerPersistentHttpStream(
              httpInterface,
              this.getTrackMediaURI(),
              this.trackInfo.length,
              this.getTrackDecryptionKey()
            )
          ) {
            processDelegate(
              new Mp3AudioTrack(this.trackInfo, stream),
              executor
            );
          }
        }
      } else {
        if (this.isPreview) {
          if (this.previewUrl == null) {
            throw new FriendlyException(
              "No preview url found",
              FriendlyException.Severity.COMMON,
              new IllegalArgumentException()
            );
          }
          try (
            var stream = new PersistentHttpStream(
              httpInterface,
              new URI(this.previewUrl),
              this.trackInfo.length
            )
          ) {
            processDelegate(
              new FlacAudioTrack(this.trackInfo, stream),
              executor
            );
          }
        } else {
          try (
            var stream = new DeezerPersistentHttpStream(
              httpInterface,
              this.getTrackMediaURI(),
              this.trackInfo.length,
              this.getTrackDecryptionKey()
            )
          ) {
            processDelegate(
              new FlacAudioTrack(this.trackInfo, stream),
              executor
            );
          }
        }
      }
    }

  @Override
  protected AudioTrack makeShallowClone() {
    return new DeezerAudioTrack(
      this.trackInfo,
      this.albumName,
      this.albumUrl,
      this.artistUrl,
      this.artistArtworkUrl,
      this.previewUrl,
      this.isPreview,
      this.sourceManager
    );
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return this.sourceManager;
  }
}
