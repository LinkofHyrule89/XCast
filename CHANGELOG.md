# Changelog

All notable changes to XCast are documented here.

## [1.2.1] — 2026-08-22

History management and an explicit unofficial disclaimer.

- Pin videos on the History tab so they stay at the top and survive **Clear**.
- Delete a single history item, or clear all unpinned items.
- README states that XCast is fully unofficial and not associated with X Corp.
- App screenshots and a 1280×640 social preview image on GitHub.

## [1.2.0] — 2026-08-22

Reliable TV playback and a YouTube Music-style bottom player.

### Playback
- Phone now proxies Twitter MP4s over LAN HTTP/1.1 so Sony Bravia / Google TV can play them (those TVs often fail on `video.twimg.com` HTTP/2).
- Advertises the Wi‑Fi address (`192.168.x`) and ignores Tailscale `100.x`, so a VPN on the phone no longer points the TV at an unreachable IP.
- Default stream is the highest Cast-safe MP4, typically **1080p** when X publishes it. ~3K remains optional and may buffer on a VPN.
- Changing **Video / resolution** reloads on the TV at the current timestamp.
- Range warmup (start and end of the file) so 1080p starts more cleanly.

### Interface
- Bottom tabs: **Cast**, **History**, **TV**.
- Mini player above the tab bar (artwork, title, play/pause, thin progress). Tap it to open now-playing (artwork, scrubber, ±10s).
- **TV** tab holds volume, mute, speed, skip, chapters, and stop when a Cast session is connected.
- History is its own tab instead of a fold on the Cast screen.
- Cast screen: instructions at the top, current video card, URL field with a search icon, Ready line, then **Cast to TV**.
- Video Playback Settings stay collapsed on the current-video card.

## [1.1.0] — 2026-08-22

Volume, mute, speed, and chapter skip on the TV remote. Recents and last-position resume. Thumbnails, Open in X, Cast intro overlay, keep-awake, and on-phone MP4 preview.

## [1.0.0] — 2026-08-22

Initial release. Share an X video post to Chromecast with Cast-safe stream picking, captions, chapters, and in-app play/pause/scrub.

[1.2.1]: https://github.com/LinkofHyrule89/XCast/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/LinkofHyrule89/XCast/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/LinkofHyrule89/XCast/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/LinkofHyrule89/XCast/releases/tag/v1.0.0
