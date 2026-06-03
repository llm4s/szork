# Decomposition — media-config-max-retries

This feature is a single, self-contained slice: add one config knob and
wire it into two media call sites. It is small enough to review in one PR,
so it is not split.

> `manifest.json` is the source of truth. This file is a human-readable
> view.

## Pieces

### p1 — Configurable media generation max-retries
Add `maxRetries` (env `SZORK_MEDIA_MAX_RETRIES`, default `2`) to
`MediaNetworkConfig`, and wrap scene-image and background-music generation
in the existing `Retry.withRetry` so retryable failures are retried up to
the configured budget. Cache hits and disabled/unavailable paths stay
outside the retry block. Spec: `pieces/p1.md`.
