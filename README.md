[![](https://jitpack.io/v/TopiSenpai/LavaSrc.svg)](https://jitpack.io/#TopiSenpai/LavaSrc)

# LavaSrc

A collection of additional [Lavaplayer](https://github.com/sedmelluq/lavaplayer) Audio Source Managers and Lavalink Plugin.
* [Spotify*](https://www.spotify.com) playlists/albums/songs/artists(top tracks)/search results
* [Apple Music*](https://www.apple.com/apple-music/) playlists/albums/songs/artists/search results(Big thx to [ryan5453](https://github.com/ryan5453) for helping me)
* [Deezer](https://www.deezer.com) playlists/albums/songs/artists/search results(Big thx to [ryan5453](https://github.com/ryan5453) and [melike2d](https://github.com/melike2d) for helping me)
* [Yandex Music](https://music.yandex.ru) playlists/albums/songs/artists/podcasts/search results(Thx to [AgutinVBoy](https://github.com/agutinvboy) for implementing it)

`*tracks are searched & played via YouTube or other configurable sources`

## Summary

* [Lavaplayer Usage](#lavaplayer-usage)
* [Lavalink Usage](#lavalink-usage)
* [Supported URLs and Queries](#supported-urls-and-queries)

## Lavaplayer Usage

Replace x.y.z with the latest version number

Snapshot builds are available in https://maven.topi.wtf/snapshots with the short commit hash as the version

### Using in Gradle:

<details>
<summary>Gradle</summary>

### 
```gradle
repositories {
  maven {
    url "https://maven.topi.wtf/releases"
  }
}

dependencies {
  implementation "com.github.TopiSenpai.LavaSrc:lavasrc:x.y.z"
}
```
</details>

### Using in Maven:

<details>
<summary>Maven</summary>

```xml
<repositories>
  <repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.TopiSenpai.LavaSrc</groupId>
    <artifactId>lavasrc</artifactId>
    <version>x.y.z</version>
  </dependency>
</dependencies>
```
</details>

### Usage

For all supported urls and queries see [here](#supported-urls-and-queries)

#### Spotify

To get a Spotify clientId & clientSecret you must go [here](https://developer.spotify.com/dashboard) and create a new application.

```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

// create a new SpotifySourceManager with the default providers, clientId, clientSecret, countryCode and AudioPlayerManager and register it
playerManager.registerSourceManager(new SpotifySourceManager(null, clientId, clientSecret, countryCode, playerManager));
```

#### Apple Music
```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

// create a new AppleMusicSourceManager with the standard providers, token(pass null for automatic extraction), countrycode and AudioPlayerManager and register it
playerManager.registerSourceManager(new AppleMusicSourceManager(null, mediaAPIToken , "us", playerManager));
```

#### Deezer
```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

// create a new DeezerSourceManager with the master decryption key and register it
playerManager.registerSourceManager(new DeezerSourceManager("...");
```

#### Yandex Music

<details>
<summary>How to get access token</summary>

## How to get access token
1. (Optional) Open DevTools in your browser and on the Network tab enable trotlining.
2. Go to https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d
3. Authorize and grant access
4. The browser will redirect to the address like `https://music.yandex.ru/#access_token=AQAAAAAYc***&token_type=bearer&expires_in=31535645`.
   Very quickly there will be a redirect to another page, so you need to have time to copy the link. ![image](https://user-images.githubusercontent.com/68972811/196124196-a817b828-3387-4f70-a2b2-cdfdc71ce1f2.png)
5. Your accessToken, what is after `access_token`.

Token expires in 1 year. You can get a new one by repeating the steps above.

## Important information
Yandex Music is very location-dependent. You should either have a premium subscription or be located in one of the following countries:
- Azerbaijan
- Armenia
- Belarus
- Georgia
- Kazakhstan
- Kyrgyzstan
- Moldova
- Russia
- Tajikistan
- Turkmenistan
- Uzbekistan

Else you will only have access to podcasts.
</details>

```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

// create a new YandexMusicSourceManager with the access token and register it
playerManager.registerSourceManager(new YandexMusicSourceManager("...");
```

---

## Lavalink Usage

This plugin requires Lavalink `v3.6.0` or greater

To install this plugin either download the latest release and place it into your `plugins` folder or add the following into your `application.yml`

Lavalink supports plugins only in v3.5 and above

> **Note**
> For a full `application.yml` example see [here](application.yml.example)

Replace x.y.z with the latest version number
```yaml
lavalink:
  plugins:
    - dependency: "com.github.TopiSenpai.LavaSrc:lavasrc-plugin:x.y.z"
      repository: "https://maven.topi.wtf/releases"
```

Snapshot builds are available in https://maven.topi.wtf/snapshots with the short commit hash as the version

### Configuration

For all supported urls and queries see [here](#supported-urls-and-queries)

To get your Spotify clientId & clientSecret go [here](https://developer.spotify.com/dashboard/applications) & then copy them into your `application.yml` like the following.

To get your Yandex Music access token go [here](#yandex-music)

(YES `plugins` IS AT ROOT IN THE YAML)
```yaml
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
      deezer: true # Enable Deezer source
      yandexmusic: true # Enable Yandex Music source
    spotify:
      clientId: "your client id"
      clientSecret: "your client secret"
      countryCode: "US" # the country code you want to use for filtering the artists top tracks. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      playlistLoadLimit: 6 # The number of pages at 100 tracks each
      albumLoadLimit: 6 # The number of pages at 50 tracks each
    applemusic:
      countryCode: "US" # the country code you want to use for filtering the artists top tracks and language. See https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2
      # mediaAPIToken: "..." # Can be used to bypass the auto token fetching which is likely to break again in the future
      playlistLoadLimit: 6 # The number of pages at 300 tracks each
      albumLoadLimit: 6 # The number of pages at 300 tracks each
    deezer:
      masterDecryptionKey: "your master decryption key" # the master key used for decrypting the deezer tracks. (yes this is not here you need to get it from somewhere else)
    yandexmusic:
      accessToken: "your access token" # the token used for accessing the yandex music api. See https://github.com/TopiSenpai/LavaSrc#yandex-music
```

---

## Supported URLs and Queries

### Spotify
* `spsearch:animals architects` (check out [Spotify Search Docs](https://developer.spotify.com/documentation/web-api/reference/search) for advanced search queries like isrc & co)
* `sprec:seed_artists=3ZztVuWxHzNpl0THurTFCv,4MzJMcHQBl9SIYSjwWn8QW&seed_genres=metalcore&seed_tracks=5ofoB8PFmocBXFBEWVb6Vz,6I5zXzSDByTEmYZ7ePVQeB`
  (check out [Spotify Recommendations Docs](https://developer.spotify.com/documentation/web-api/reference/get-recommendations) for the full query parameter list)
* https://open.spotify.com/track/0eG08cBeKk0mzykKjw4hcQ
* https://open.spotify.com/album/7qemUq4n71awwVPOaX7jw4
* https://open.spotify.com/playlist/7HAO9R9v203gkaPAgknOMp
* https://open.spotify.com/artist/3ZztVuWxHzNpl0THurTFCv

(including new regional links like https://open.spotify.com/intl-de/track/0eG08cBeKk0mzykKjw4hcQ)

### Apple Music
* `amsearch:animals architects`
* https://music.apple.com/cy/album/animals/1533388849?i=1533388859
* https://music.apple.com/cy/album/for-those-that-wish-to-exist/1533388849
* https://music.apple.com/us/playlist/architects-essentials/pl.40e568c609ae4b1eba58b6e89f4cd6a5
* https://music.apple.com/cy/artist/architects/182821355

### Deezer
* `dzsearch:animals architects`
* `dzisrc:USEP42058010`
* https://deezer.page.link/U6BTQ2Q1KpmNt2yh8
* https://www.deezer.com/track/1090538082
* https://www.deezer.com/album/175537082
* https://www.deezer.com/playlist/8164349742
* https://www.deezer.com/artist/159126

### Yandex Music
* `ymsearch:animals architects`
* https://music.yandex.ru/album/13886032/track/71663565
* https://music.yandex.ru/album/13886032
* https://music.yandex.ru/users/yamusic-bestsongs/playlists/701626
* https://music.yandex.ru/artist/701626

---
