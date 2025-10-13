# Refactoring Plan

A living plan to improve the Szork engine/server for testability, safety, and maintainability. Items are grouped by phase and prioritized within each phase.

## Phase 1 — Architecture & Boundaries
- [x] Extract pure core for state/rules/navigation (CoreState/CoreEngine)
- [x] SPI for side-effects (LLM, TTS, STT, Image, Music, Persistence, Clock, RNG)
- [x] DI in GameEngine (Clock + SPI clients), no deep env reads
- [x] Inject LLMClient at server/test boundary (no EnvLoader in deep code)

## Phase 2 — Configuration & Feature Flags (In Progress)
- [x] Centralize flags in `SzorkConfig` (ttsEnabled, sttEnabled, musicEnabled, imageGenerationEnabled)
- [x] Gate behaviors in `TypedWebSocketServer` and startup logs; add `/api/feature-flags`
- [ ] Per-session overrides via `NewGameRequest` (optional: `tts`, `stt`, `music`, `image`) and echo in `GameStartedMessage`
- [x] Per-session overrides wired in backend and consumed by frontend (UI disables controls)
- [x] Frontend capability chips in selection screen using `/api/feature-flags`
- [x] README docs for flags and protocol fields

## Phase 3 — Protocol & JSON Validation
- [x] `GameResponseValidator` with field checks, allowed moods, allowed directions, exits required for full scenes
- [x] Centralized parsing: `GameResponseParser`, `AdventureOutlineParser`, `GameStateCodec`
- [x] Harden rules: narration/image/music length budgets, ID normalization pattern
- [x] Propagate validation failures to clients via `ErrorMessage` (initial, command, streaming, audio)
- [ ] Add scene-change specific checks and consistent ID reuse
- [ ] Explore function-calling or strict schema enforcement via LLM client

## Phase 4 — Testing Strategy
- [x] Deterministic `FakeLLMClient` for engine unit tests (no network)
- [x] Unit tests: parser/validator (`ParserValidatorSpec`), core state (`CoreEngineSpec`), engine E2E with fake LLM
- [ ] Unit test: engine validation path (invalid exits) with failing fake LLM
- [ ] Concurrency tests: `SessionManager`, async image/music generation
- [ ] Tag integration tests and add sbt alias (e.g., `itOnly`); skip network tests by default in CI
- [ ] Property tests for parser robustness and state invariants

## Phase 5 — Observability & Safety
- [x] Startup feature availability logging
- [x] Demote verbose LLM logs to DEBUG; truncate previews
- [ ] Metrics: timers/counters around LLM calls, image/music latencies, cache hit/miss
- [ ] Redact secrets; hash long prompts/IDs in logs; input size limits (audio/command length)

## Phase 6 — Error Handling & Resilience
- [ ] Standardize retry/backoff helpers for network calls
- [ ] Normalize error shapes for API/WebSocket (introduce `/api/v1/*`), return clear 4xx/5xx
- [ ] Global timeouts; centralized exception mapping

## Phase 7 — Caching & Storage
- [x] Persist media as files with TTL/size limits in `szork-cache/` (env: `SZORK_CACHE_TTL_MS`, `SZORK_CACHE_MAX_BYTES`); automatic eviction implemented
- [x] Normalize cache keys by provider + style/mood + description (hashed filenames)
- [x] Centralize MediaCache metadata with a codec (`MediaCacheCodec`, mirrors `GameStateCodec`)

## Phase 8 — Performance & Concurrency
- [ ] Name/bound thread pools; instrument queue sizes
- [ ] Backpressure in WS paths; in-flight de-dupe for image/music per key

## Phase 9 — Frontend Integration
- [ ] Consume `/api/feature-flags` to show capability badges/toggles
- [ ] Handle per-session flags in new-game flow; disable UI affordances accordingly
- [ ] Surface validation errors to users (e.g., “response invalid, please retry”)

## Phase 10 — CI/CD & Docs
- [ ] CI: unit (fast) by default; opt-in integration jobs
- [ ] Update READMEs with flags, endpoints, and testing recipes
- [ ] Troubleshooting: provider/keys, timeouts, cache permissions

## Acceptance Criteria
- [ ] Engine core + parser/validator covered by unit tests (no network)
- [ ] Validator rejects malformed responses; user receives clear error
- [ ] Per-session flags respected end-to-end and visible in UI
- [ ] CI runs unit suite by default; integration suite opt-in

## Current Priorities
1) Phase 3: Add scene-change checks, consistent ID reuse hints; refine client messaging and error UX
2) Phase 5: Add basic metrics around LLM/image/music/cache (timers, counters)
3) Phase 8: Backpressure and in-flight de-dupe for media generation
4) Phase 2: Minor frontend polish (show flags in setup banner); finalize docs
