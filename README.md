# XCast

Android app that casts videos from [X](https://x.com) (Twitter) posts to a Chromecast or Cast-enabled TV using the [Google Cast SDK](https://developers.google.com/cast).

Share a video post from the X app, pick a Cast-safe resolution, and play it on the big screen. XCast is the remote: play/pause, scrub, captions, and chapters.

## Features

- **Share to XCast** from the X app (`text/plain` share and `x.com` / `twitter.com` links)
- Resolves a direct H.264 + AAC stream (no X API key)
- **Cast-safe resolution picker** so Chromecast is not sent 3K / 25 Mbps files
- Audio track and **closed captions / WebVTT** when X publishes them
- **Chapters** from timestamp lines in the post (`0:00 Intro`)
- In-app **TV remote**: play, pause, ±10s, scrubber, stop, **volume, mute, speed**, chapter skip
- **Resume** from last position, **recents** (tap to re-open a post)
- Thumbnail, duration, **Open in X**, on-phone **MP4 preview** before Cast
- Material You dynamic color, Cast intro overlay, keep-screen-on while casting

## Install

1. Download `XCast.apk` from the latest [GitHub Release](https://github.com/LinkofHyrule89/XCast/releases).
2. On your phone: Settings → allow install from the browser/Files app.
3. Phone and TV on the same Wi‑Fi. Google Play Services required.

Debug builds from CI are signed with the Android **debug** keystore so they sideload easily. They are not Play-store signed.

## Use

1. Open a **video** post in X.
2. Share → **XCast**, or paste `https://x.com/user/status/…` and tap **Find video**.
3. Choose a **Castable** resolution (720p is the default) and audio.
4. Tap the Cast icon, pick your TV, then **Cast to TV**.
5. Control playback from the **TV remote** card.

Private posts, login-walled media, and some HLS-only clips may not resolve.

## Build

Requires JDK 17 and the Android SDK (compile SDK 36).

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

Or open the folder in Android Studio.

GitHub Actions builds the APK on every push to `main` and publishes it when you push a tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## How it works

1. A share/VIEW intent delivers the post URL.
2. The status ID is sent to FxTwitter / VxTwitter JSON endpoints.
3. Variants are ranked for Chromecast (prefer muxed 720p MP4).
4. Google Cast **Default Media Receiver** (`CC1AD845`) plays HTTPS media on the TV.

Lookups and media URLs are restricted to HTTPS hosts on X/Twitter CDNs and the public lookup APIs. Cleartext HTTP is disabled.

## Privacy

XCast does not require an X account or API token. Lookups go to third-party public tweet-embed APIs. No analytics SDK is bundled. Backup is disabled.

## License

MIT. X, Twitter, Chromecast, and Google Cast are trademarks of their owners. This project is not affiliated with X Corp or Google.
