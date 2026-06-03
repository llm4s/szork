# Design — media-config-max-retries

## Problem

Scene-image and background-music generation are network-bound calls to
external providers (HuggingFace, OpenAI/DALL-E, local Stable Diffusion,
Replicate). Transient failures — timeouts, 5xx, dropped connections — are
common. The media generators already classify these failures as
`retryable = true` (see `ImageGeneration.scala`, `MusicGeneration.scala`)
and the codebase already ships a `Retry.withRetry` utility in
`error/ErrorHandling.scala`, **but no retry is actually wrapped around the
media generation calls today**. A single transient blip therefore drops
the image or music for that turn.

We want media generation to retry transient failures a configurable number
of times, following the established `MediaNetworkConfig` pattern (an
env-overridable knob with a sane default).

## Approach

1. Add a `maxRetries` field to `MediaNetworkConfig` (the existing media
   config holding the shared connect/read timeouts), loaded from a new
   `SZORK_MEDIA_MAX_RETRIES` environment variable. Invalid or negative
   values fall back to the default, matching how the timeout fields parse.

2. Wrap the two media generation calls in the existing
   `ErrorHandling.Retry.withRetry`, using `maxAttempts = maxRetries + 1`:
   - `ImageGeneration.generateSceneWithCache` — around the provider
     `generateImage` call.
   - `MusicGeneration.generateMusicWithCache` — around the generation
     request.

   `withRetry` already honours the `retryable` flag and applies
   exponential backoff, so only failures the generators marked retryable
   are retried. The cache-hit fast paths must stay outside the retry
   block (a cache hit must not be retried or counted as an attempt).

3. Surface the value in `MediaNetworkConfig` logging consistent with how
   other config is logged, so operators can see the effective setting.

## Decisions taken

These were proposed to the human; the clarifying question was dismissed,
so the recommended defaults below were adopted and are open to revision
before implementation:

- **Scope:** Image + Music only. TTS and STT are out of scope for v1.
- **Default:** `maxRetries = 2` (3 total attempts) when the env var is
  unset. This is a deliberate, small behavioural change from today's
  no-retry path, chosen so the feature adds resilience out of the box.
- **Semantics:** `SZORK_MEDIA_MAX_RETRIES` is the number of *retries*
  (additional attempts), not total attempts. `0` disables retrying and
  reproduces today's exact behaviour.
- **Location:** the setting lives on `MediaNetworkConfig`, alongside the
  existing media timeout knobs, rather than on `SzorkConfig`.

## Non-goals

- No retry for text-to-speech, speech-to-text, or LLM/narrative calls.
- No change to the existing backoff strategy, `withRetry` utility, or the
  `retryable` classification of any error.
- No change to `MusicGeneration.pollPrediction`'s internal poll loop
  (`maxAttempts = 30`); that is a status poll, not a failure retry.
- No new retry config surface beyond the single `SZORK_MEDIA_MAX_RETRIES`
  env var (no per-provider knobs).
