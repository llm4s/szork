# music-poll-config

## Problem

`MusicGeneration.pollPrediction`
(`src/main/scala/org/llm4s/szork/media/MusicGeneration.scala`) hardcodes its
polling behaviour:

- `maxAttempts: Int = 30` (method default parameter)
- `Thread.sleep(1000)` between polls (the "Wait 1 second before polling again" line)

Neither value is configurable, so a deployment cannot tune how long Szork waits
for a Replicate music prediction to finish.

This mirrors the gap that `MediaNetworkConfig`
(`src/main/scala/org/llm4s/szork/api/MediaNetworkConfig.scala`) already closed for
the media HTTP connect/read timeouts.

## Approach

Add a small `MusicPollConfig` case class + companion in the `org.llm4s.szork.api`
package, modelled exactly on `MediaNetworkConfig`:

- Fields with defaults equal to the current literals:
  - `maxAttempts: Int = 30`
  - `pollIntervalMs: Int = 1000`
- An env-overridable `load(reader: ConfigReader = EnvLoader)` reading
  `SZORK_MUSIC_POLL_MAX_ATTEMPTS` and `SZORK_MUSIC_POLL_INTERVAL_MS` (each parsed
  via `Try(_.toInt).toOption`, falling back to the defaults when absent or
  unparseable), plus a `lazy val instance = load()`.
- Route `MusicGeneration.pollPrediction` through `MusicPollConfig.instance`: the
  `maxAttempts` default parameter becomes `MusicPollConfig.instance.maxAttempts`
  and `Thread.sleep(1000)` becomes
  `Thread.sleep(MusicPollConfig.instance.pollIntervalMs)`. Default behaviour is
  unchanged (defaults equal the old literals).
- A unit test `MusicPollConfigSpec` mirroring `MediaNetworkConfigSpec`: defaults
  equal the historical literals, and the two env vars override (including
  unparseable-value fallback).

## Non-goals

- No change to the polling logic itself, the HTTP client, or any other media class.
- No change to `MediaNetworkConfig`.

## Decisions

Single piece **p1** — small, self-contained: add `MusicPollConfig`, route
`pollPrediction` through it, add `MusicPollConfigSpec`.
