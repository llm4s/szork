# Design — adventure-gen-retry-config

## Problem

`AdventureGenerator.generateAdventureOutline`
(`src/main/scala/org/llm4s/szork/game/AdventureGenerator.scala`) hardcodes
two retry/back-off values inline:

- `val maxRetries = 2` (around line 196), controlling how many times
  adventure-outline generation is retried.
- `Thread.sleep(500)` (lines 218 and 226), the back-off pause between
  retry attempts.

These literals cannot be tuned per deployment. A flaky or slow LLM
backend may warrant more retries or a longer back-off; a latency-sensitive
deployment may want fewer/shorter. Today the only way to change either is
to edit and recompile the source.

## Approach

Introduce a small configuration object `AdventureGenConfig`, modelled
exactly on the existing `org.llm4s.szork.api.MusicPollConfig`
(`src/main/scala/org/llm4s/szork/api/MusicPollConfig.scala`):

- A `case class AdventureGenConfig(maxRetries: Int = 2, retryBackoffMs: Int = 500)`
  whose field defaults equal the current inline literals, so default
  runtime behaviour is unchanged.
- A companion `object` with:
  - `lazy val instance: AdventureGenConfig = load()`
  - `def load(reader: ConfigReader = EnvLoader): AdventureGenConfig` that
    reads each value from an environment override, falling back to the
    default when the override is absent or unparseable (using the same
    `reader.get(...).flatMap(v => Try(v.toInt).toOption).getOrElse(default)`
    pattern as `MusicPollConfig`).

Environment overrides:

| Field            | Default | Env var                              |
|------------------|---------|--------------------------------------|
| `maxRetries`     | `2`     | `SZORK_ADVENTURE_GEN_MAX_RETRIES`    |
| `retryBackoffMs` | `500`   | `SZORK_ADVENTURE_GEN_RETRY_BACKOFF_MS` |

`AdventureGenerator.generateAdventureOutline` is then rewired to read
`AdventureGenConfig.instance` once at the top of the retry loop and use
`config.maxRetries` in place of the local `val maxRetries` and
`config.retryBackoffMs` in place of the two `Thread.sleep(500)` literals.

### Package placement

`AdventureGenConfig` is a config peer of `MusicPollConfig`. To keep config
objects together and follow the existing convention, place it in the same
package as `MusicPollConfig`: `org.llm4s.szork.api`, file
`src/main/scala/org/llm4s/szork/api/AdventureGenConfig.scala`.
`AdventureGenerator` (in `org.llm4s.szork.game`) imports it. This keeps the
change confined to the `game` and `api` packages as required by the brief.

### Documentation (acceptance criterion)

`AdventureGenConfig` is treated as public API and MUST carry ScalaDoc on:

1. The `case class` — explaining its purpose and that defaults equal the
   historical inline literals.
2. The companion `load` factory method — using explicit `@param` and
   `@return` tags, one per parameter, each a complete, naturally-reading
   sentence on a single line. See `pieces/p1.md` for the exact required
   shape.

## Non-goals

- No change to default runtime behaviour (defaults equal current literals).
- No change to the retry/parse logic itself, the LLM prompt, or any other
  behaviour of `AdventureGenerator`.
- No new config keys beyond the two named above.
- No changes outside the `game` and `api` packages.
- No broader configuration framework or refactor of `MusicPollConfig`.

## Decisions taken

- **Package**: `org.llm4s.szork.api` (alongside `MusicPollConfig`), rather
  than `org.llm4s.szork.game`, to keep config objects co-located and
  consistent. The brief permits both packages.
- **Single piece**: the change is small and self-contained; it ships as
  one reviewable PR.
