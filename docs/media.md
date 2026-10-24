# Video, sound and options

## Recording video

`mc.client.video.start` records the game window to an mp4 (H.264, AAC) through
[ffmpeg](https://ffmpeg.org); `mc.client.video.stop` finishes the file and returns it. A recording keeps
going across tool calls and scripts, and stops on its own after `seconds` (default 120, at most 1800).

```json
{"name": "mc.client.video.start", "arguments": {"name": "farm-run", "fps": 20, "maxWidth": 1280}}
{"name": "mc.client.video.stop", "arguments": {}}
```

The answer of `stop`: `path` (under `videos/` in the game directory), `seconds`, `frames`, `captured`,
`dropped` (frames the encoder could not keep up with), `source` (the framebuffer size), `sounds` (how many
sounds the track holds) and `bytes`. `mc.client.video.state` reports a recording in progress.

- **Frames.** One readback of the framebuffer per game tick at most, so `fps` is 1-20. The frames go to
  ffmpeg as raw pixels on their own thread; the game thread only copies them out. The timeline follows the
  wall clock: when the game lags, the previous frame is repeated, so the video and its sound stay in step.
  A hidden window (`mc.client.window`) still renders and records.
- **ffmpeg** is looked up on the `PATH`, then in `/opt/homebrew/bin`, `/usr/local/bin` and `/usr/bin`. A
  launcher often starts the game with a short `PATH`: pass `ffmpeg: "/path/to/ffmpeg"` (it must be named
  `ffmpeg`) or set `MINECRAFT_MCP_FFMPEG`.
- **Encoder.** H.264 with the fastest encoder the ffmpeg has: libx264 on the `veryfast` preset (its default
  preset falls behind a running game and frames get dropped), else VideoToolbox, NVENC or OpenH264; `state`
  reports which (`codec`). Frame buffers are reused, and at most about 256 MB of frames wait for the encoder.
- **Sound.** The game's audio output cannot be tapped from inside the process (OpenAL plays it straight to
  the device). The track is rebuilt instead: every sound the game starts is recorded with its time, position,
  volume and pitch, and when the recording stops the same `.ogg` files are decoded and mixed the way the
  game's OpenAL setup plays them: linear distance attenuation, pitch, the channel volumes (with the formula of
  the game version: it changed in 1.21.11), mono sounds panned by where they are relative to the player,
  stereo ones (music, records) as they are, loops until they stop. Sounds that change while they play
  (minecarts, elytra wind, bees) are followed every tick: volume, pitch and position. While the game is
  paused, every sound but music and UI holds still, as in the game. This is the same approach as live game
  audio recorders such as PeterWolf's Replay Audio Recorder; OpenAL's loopback device, which replay mods
  use when exporting, would take the audio away from the speakers. Not in it: sounds from other mods' own audio engines (voice chat mods, for example).
  `audio: false` records video only.

## Sounds the game played

`mc.client.sound.events` lists the sounds the client started playing: `id`
(`minecraft:entity.zombie.ambient`), `subtitle` (as the subtitles show it), `source` (the volume channel),
`x`/`y`/`z`, `distance`, `volume`, `pitch`. The newest 500 are kept. Without `since` it answers the newest
`limit` sounds; pass the returned `last` as `since` next time to get only what played after. `contains` filters
by id, subtitle or channel.

## Options

`mc.client.options` returns the client options; `set` changes some:

```json
{"name": "mc.client.options", "arguments": {"set": {"sound.master": 0, "pauseOnLostFocus": false}}}
```

- Sound: `sound.master`, `sound.music`, `sound.record`, `sound.weather`, `sound.block`, `sound.hostile`,
  `sound.neutral`, `sound.player`, `sound.ambient`, `sound.voice` (and `sound.ui` where the game has it),
  0 to 1. `sound.master: 0` mutes the game.
- Video and controls: `fov`, `renderDistance`, `simulationDistance`, `framerateLimit`,
  `inactivityFpsLimit`, `gamma`, `guiScale`, `enableVsync`, `fullscreen`, `particles`, `cloudStatus`,
  `entityShadows`, `bobView`, `sensitivity`, `autoJump`, `smoothCamera`, `showSubtitles`,
  `chatVisibility`, `narrator`.
- `pauseOnLostFocus`: with the default `true`, a singleplayer world pauses (ticks stop) as soon as the
  window loses focus, so an agent driving the game in the background should set it to `false`.

A change applies at once. All or nothing: an unknown name or a value out of range leaves every option as
it was. `save: true` also writes `options.txt`, so the change survives a restart.
