Pause a proxied MP4 and the phone keeps downloading it into a temporary cache. The TV can read those saved bytes on a later request. Playback stays where you paused it.

- Works for the MP4 currently playing through the phone’s LAN proxy. Adaptive HLS is unchanged.
- One video at a time, up to 512 MB. The save also stops if free space would drop below 256 MB.
- Playing again stops the extra download and keeps what was already saved. Stop, disconnect, or a different video deletes it.
- The status line describes the phone save only. It is not the TV’s buffer and does not promise a number of seconds ahead.
- If a requested range is not saved yet, or the save fails, the TV streams from the network as before. Cast is not reloaded.
- The save runs only while the XCast process is open. There is no background download service.
- This build is signed the same way as earlier GitHub releases, with the CI debug key. Those keys are not kept, so Android may refuse to install this over the copy you already have.
