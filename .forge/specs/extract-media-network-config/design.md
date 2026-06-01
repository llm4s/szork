# extract-media-network-config

## Problem

The three media HTTP clients each hardcode the same network timeout pair
inline at every request site:

- `media/TextToSpeech.scala` — `synthesize()` POST to OpenAI:
  `readTimeout = 30000`, `connectTimeout = 10000` (~lines 37-38).
- `media/SpeechToText.scala` — `transcribe()` POST to OpenAI:
  `readTimeout = 30000`, `connectTimeout = 10000` (~lines 32-33).
- `media/MusicGeneration.scala` — `generateMusicWithCache()` create-prediction
  POST to Replicate: `readTimeout = 30000`, `connectTimeout = 10000`
  (~lines 215-216).
- `media/MusicGeneration.scala` — `pollPrediction()` GET to Replicate:
  `readTimeout = 30000`, `connectTimeout = 10000` (~lines 277-278).
- `media/MusicGeneration.scala` — `downloadAndEncodeAudio()` raw
  `URLConnection`: `setConnectTimeout(10000)`, `setReadTimeout(30000)`
  (~lines 325-326).

The same two magic numbers (`30000` read, `10000` connect) are copy-pasted
across five sites in three files. There is no single place to see or change
the media network timeouts, and the values cannot be tuned per deployment
(e.g. a slow local model server or a flaky network) without editing source
in multiple spots that will drift apart.

## Approach

Backend de-duplication plus light configurability, scoped to the media
HTTP timeouts only. Mirrors the existing `image-creds-dedup` refactor in
style (small surgical change, injectable `ConfigReader` for testing,
env-overridable defaults).

Add a small `MediaNetworkConfig` case class in the `api` package
(`api/MediaNetworkConfig.scala`, next to `SzorkConfig`):

```scala
case class MediaNetworkConfig(
  connectTimeoutMs: Int = 10000,
  readTimeoutMs: Int = 30000
)
```

with a loader and a lazily-evaluated shared instance, following the exact
shape of `SzorkConfig`:

- `MediaNetworkConfig.load(reader: ConfigReader = EnvLoader): MediaNetworkConfig`
  reads two env vars, falling back to the current literals on absent or
  unparseable values:
  - `SZORK_MEDIA_CONNECT_TIMEOUT_MS` → `connectTimeoutMs` (default `10000`).
  - `SZORK_MEDIA_READ_TIMEOUT_MS` → `readTimeoutMs` (default `30000`).
  - Parsing uses `Try(_.toInt).toOption`; an unparseable value uses the
    default (consistent with how `SzorkConfig` loads `SZORK_PORT`).
- `lazy val instance: MediaNetworkConfig = load()` — the shared production
  value, matching `SzorkConfig.instance`.

Then route every timeout site through it:

- `TextToSpeech` and `SpeechToText` each hold a
  `private val net = MediaNetworkConfig.instance` and pass
  `connectTimeout = net.connectTimeoutMs`, `readTimeout = net.readTimeoutMs`
  to their `requests` call.
- `MusicGeneration` does the same for both the create-prediction POST and
  the `pollPrediction` GET, and uses `net.connectTimeoutMs` /
  `net.readTimeoutMs` in `downloadAndEncodeAudio`'s
  `setConnectTimeout` / `setReadTimeout`.

The defaults equal the current literals, so default behaviour is
byte-for-byte unchanged.

## Testing

Add one ScalaTest spec (`MediaNetworkConfigSpec.scala`, matching the
existing suite) that drives `MediaNetworkConfig.load` with a fake
`ConfigReader`:

- Absent env vars ⇒ defaults `connectTimeoutMs = 10000`,
  `readTimeoutMs = 30000`.
- Both env vars set to valid integers ⇒ parsed values are used.
- An unparseable value (e.g. `"abc"`) ⇒ falls back to the default for that
  field, independently of the other.

## Non-goals

- Music poll settings (`maxAttempts = 30`, the `Thread.sleep(1000)` poll
  interval) are **not** extracted — timeouts only.
- The service base URLs (`api.openai.com`, `api.replicate.com`) and the
  Replicate model version string are **not** extracted; they are unique
  per service, not duplicated, and out of scope for this pass.
- No change to credentials, retry/`retryable` logic, request bodies,
  headers, or any user-visible behaviour.
- No change to default timeout values — only where they are defined.

## Decisions

- Timeouts only, env-overridable with defaults, in a dedicated
  `MediaNetworkConfig` type. (Proposed alternatives — bundling poll
  settings/URLs, constants-only with no env overrides, or adding fields to
  `SzorkConfig` — were set aside in favour of the tightest match to the
  feature name and the existing config conventions.)
- The new type lives in the `api` package beside `SzorkConfig`, where the
  other config types and env-loading live, rather than in the `media`
  package.
- Injectable `ConfigReader` defaulting to `EnvLoader`, exactly as
  `image-creds-dedup` did, so the loader is unit-testable without touching
  process env. Production call sites use `MediaNetworkConfig.instance`.
