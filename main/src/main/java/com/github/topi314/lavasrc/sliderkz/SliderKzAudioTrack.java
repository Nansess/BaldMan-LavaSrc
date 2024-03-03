package com.github.topi314.lavasrc.sliderkz;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SliderKzAudioTrack extends DelegatedAudioTrack {

  private final SliderKzSourceManager sourceManager;
  private static final Logger log = LoggerFactory.getLogger(
    SliderKzAudioTrack.class
  );

  public SliderKzAudioTrack(
    AudioTrackInfo trackInfo,
    SliderKzSourceManager sourceManager
  ) {
    super(trackInfo);
    this.sourceManager = sourceManager;
  }

  @Override
  public void process(LocalAudioTrackExecutor executor) throws Exception {
    var downloadLink = this.trackInfo.uri;
    try (var httpInterface = this.sourceManager.getHttpInterface()) {
      try (
        var stream = new PersistentHttpStream(
          httpInterface,
          new URI(downloadLink),
          this.trackInfo.length
        )
      ) {
        processDelegate(new Mp3AudioTrack(this.trackInfo, stream), executor);
      }
    }
  }

  @Override
  protected AudioTrack makeShallowClone() {
    return new SliderKzAudioTrack(this.trackInfo, this.sourceManager);
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return this.sourceManager;
  }
}
