# BaldMan LavaSrc

## Purpose

BaldMan LavaSrc is a fork with a simple goal: provide an alternative method to interact with audio sources like Spotify and Apple Music without the pain of managing API keys and rate Limits. The intent is to simplify the setup process for developers who prefer a straightforward approach to using these platforms.

## Key Features

- **Spotify :**  search Spotify without the need for a client ID or secret.
- **Apple Music :**  Apple Music features without requiring an API key.

## Upcoming Plans

- **Deezer ARL Support:** We're working on adding ARL support for Deezer, which will allow 1000kbps+ Playback, 320kbps, and the regular 120kbps

## Getting Started

Check out the example application file to understand the setup and diffrence from the Orginal LavaSrc

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
      spotify: true # Enable Spotify source
      applemusic: true # Enable Apple Music source
      deezer: false # Enable Deezer source
      yandexmusic: false # Enable Yandex Music source
    spotify:
      countryCode: "US" # the country code you want to use for filtering the artists top tracks. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      playlistLoadLimit: 6 # The number of pages at 100 tracks each
      albumLoadLimit: 6 # The number of pages at 50 tracks each
    applemusic:
      countryCode: "US" # the country code you want to use for filtering the artists top tracks and language. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      playlistLoadLimit: 6 # The number of pages at 300 tracks each
      albumLoadLimit: 6 # The number of pages at 300 tracks each
    deezer:
      masterDecryptionKey: "your master decryption key" # the master key used for decrypting the deezer tracks. (yes this is not here you need to get it from somewhere else)
    yandexmusic:
      accessToken: "your access token" # the token used for accessing the yandex music api. See https://github.com/TopiSenpai/LavaSrc#yandex-music
```

Feel free to contribute, report issues, or share your feedback. All Credits Go To Topi (as this is a fork of LavaSrc(https://github.com/topi314/LavaSrc)
