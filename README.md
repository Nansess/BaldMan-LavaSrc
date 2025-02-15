# BaldMan LavaSrc

> [!WARNING]  
> DO NOT USE LAVASRC and BALDMAN LAVASRC at the same time. Mixing these plugins may lead to unexpected behavior or errors. Choose one plugin and ensure it meets your requirements before using it in your project.

## Purpose

BaldMan LavaSrc is a fork of the original LavaSrc project aimed at providing a simpler, plug-and-play experience when integrating audio sources like Spotify, Apple Music, Deezer, and Tidal into Lavalink without the hassle of managing API keys.

Since the creation of this fork, the original LavaSrc (located at [topi314/LavaSrc](https://github.com/topi314/LavaSrc)) has integrated some of the enhancements developed here, such as automatic extraction for Apple Music and Spotify. However, this fork does not include newer LavaSrc features like lyrics support and LavaSearch, as the focus remains on simplifying the user experience.

> [!TIP]  
> When using Spotify with this Fork, note that while the usual Spotify API enforces rate limits based on the application's client secret and ID, this fork uses anonymous tokens instead. This approach helps avoid rate limits tied to client credentials but be aware of potential IP rate limits. This fork does not include newer LavaSrc features like lyrics support and LavaSearch.

## Key Features

- **Spotify**: Interact without the need for a client ID or secret.
- **Apple Music**: Access without requiring an API key.
- **Deezer**: Use without needing a Master key.
- **Tidal**: Operate without requiring a key

## Future of This Fork

This fork will continue to support Tidal until such features are integrated into the main LavaSrc repository. After these integrations are complete, BaldMan LavaSrc will be phased out and archived, as the main repository will then cover all intended functionalities.

## Getting Started

Check out the example application file to understand the setup


```
plugins:
    - dependency: "com.github.Nansess.BaldMan-LavaSrc:baldman-plugin:v4.5.0"
      repository: "https://jitpack.io"
```

```
plugins:
  lavasrc:
    providers: # Custom providers for track loading. This is the default
      # - "dzisrc:%ISRC%" # Deezer ISRC provider
      # - "dzsearch:%QUERY%" # Deezer search provider
      - "ytsearch:\"%ISRC%\"" # Will be ignored if track does not have an ISRC. See https://en.wikipedia.org/wiki/International_Standard_Recording_Code
      - "ytsearch:%QUERY%" # Will be used if track has no ISRC or no track could be found for the ISRC
      #  you can add multiple other fallback sources here
    sources:
      spotify: false # Enable Spotify source
      applemusic: false # Enable Apple Music source
      deezer: false # Enable Deezer source
      yandexmusic: false # Enable Yandex Music source
      tidal: false
      flowerytts: false # Enable Flowery TTs source
      youtube: true # Enable YouTube search source (https://github.com/topi314/LavaSearch)
    spotify:
      countryCode: "US" # the country code you want to use for filtering the artists top tracks. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      playlistLoadLimit: 6 # The number of pages at 100 tracks each
      albumLoadLimit: 6 # The number of pages at 50 tracks each
    applemusic:
      countryCode: "US" # the country code you want to use for filtering the artists top tracks and language. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      playlistLoadLimit: 6 # The number of pages at 300 tracks each
      albumLoadLimit: 6 # The number of pages at 300 tracks each
    tidal:
      countryCode: "US"
      searchLimit: 6
    yandexmusic:
      accessToken: "your access token" # the token used for accessing the yandex music api. See https://github.com/TopiSenpai/LavaSrc#yandex-music
    flowerytts:
      voice: "default voice" # (case-sensitive) get default voice here https://flowery.pw/docs/flowery/tts-voices-v-1-tts-voices-get
      translate: false # whether to translate the text to the native language of voice
      silence: 0 # the silence parameter is in milliseconds. Range is 0 to 10000. The default is 0.
      speed: 1.0 # the speed parameter is a float between 0.5 and 10. The default is 1.0. (0.5 is half speed, 2.0 is double speed, etc.)
      audioFormat: "mp3" # supported formats are: mp3, ogg_opus, ogg_vorbis, aac, wav, and flac. Default format is mp3
```

Feel free to contribute, report issues, or share your feedback.
