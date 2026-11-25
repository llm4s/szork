# Testing Szork

Szork is tested in four layers. The first three run in `sbt test` with no network; the fourth calls a real model.

| Layer | What it shows | Where |
|---|---|---|
| Scripted | The game's logic: the engine, tools, world state, saves, sessions, the WebSocket protocol | most suites; `stubs/ScriptedDm`, `stubs/DmAnswers` |
| Wire | The requests Szork's agents send, read from llm4s's real Anthropic client talking to a local server | `WireFormatSpec`, `stubs/FakeAnthropic` |
| Invariants | That the world stays consistent whatever the model answers, over seeded random games | `WorldInvariantsSpec` |
| Playtest | A designed adventure, played start to finish against a real model and held to the game's rules | `playtest/Playtest`, `AdventureIntegrationSpec` |

```bash
sbt test                                  # the first three layers
sbt "testOnly *WireFormatSpec"            # one suite
sbt "testOnly * -- -z inventory"          # tests whose name matches
cd frontend && npm test                   # frontend tests (Vitest)
```

CI (`.github/workflows/ci.yml`) also compiles main and test sources with `-Werror`, checks `sbt scalafmtCheckAll`,
and runs the frontend's `npm run lint:check`, tests and build.

## Scripted tests

The llm4s testkit's `ScriptedLLMClient` answers each model call from a rule over the conversation and records every
request. `ScriptedDm` plays a two-room cavern and takes a test's own rules in front of it; `DmAnswers` builds answers
in the Dungeon Master's output format. `SzorkServerSpec` and `WebSocketServerSpec` start a whole server on scripted
models.

A scripted client sits above the provider, so it cannot show a request the provider would refuse.

## Wire tests

`FakeAnthropic` is a local HTTP server that answers like the Anthropic Messages API and keeps each request body.
`WireFormatSpec` points Szork's real client at it and asserts on what was sent: no `temperature` for the models that
refuse one, the outline designer's `max_tokens`, the Dungeon Master's tools and system prompt. Add a test here when a
change is about what a provider receives.

## Invariant tests

`WorldInvariantsSpec` plays seeded random games against a Dungeon Master that answers anything: scenes through shut
exits, places it makes up, exits changed by tool calls. After every turn it checks that no move went through an exit
that was not open, that the scene and the world agree on where the player is, that the map is still well formed, and
that the game saves and loads as it is. A failure names its seed; replay it by calling `play(seed, turns)`.

## Playtests

A playtest designs an adventure with the configured model, plays it with a model as the player, and holds every turn
to `PlaytestChecks`:

```bash
sbt "runMain org.llm4s.szork.playtest.Playtest"
sbt "runMain org.llm4s.szork.playtest.Playtest --adventure the-last-toll-of-ravenmoor"   # a library adventure: the same outline every run
sbt "runMain org.llm4s.szork.playtest.Playtest --theme 'a derelict space station' --turns 40 --report playtest.json"
RUN_INTEGRATION=true sbt "testOnly *AdventureIntegrationSpec"     # the same, as a test (20 turns)
```

Options: `--theme <text>`, `--turns <n>` (default 25), `--report <file.json>` (every turn and finding), `--no-probes`.
The exit status is 0 when the game kept its rules, 1 on a hard finding, 2 when the playtest could not run.

| Check | Hard | Meaning |
|---|---|---|
| `outline-map` | when a location cannot be reached | The designed map has a problem `OutlineMap.problems` reports |
| `turn-failed` | yes | A turn returned an error (a step limit, a provider refusal) |
| `empty-narration`, `raw-answer` | yes | The player was shown nothing, or the model's JSON |
| `closed-exit-used` | yes | The player moved through an exit that was not open |
| `locked-exit-walked` | yes | Walking at a locked exit of the start location, in a game of its own, got through |
| `location-mismatch` | yes | The scene shown and the world state disagree on where the player is |
| `command-refused` | no | The input guardrail refused a command the player model typed |
| `carried-item-in-scene` | no | An item is both carried and listed in the scene |
| `repeated-narration` | no | A narration shares most of its words with an earlier one |
| `many-model-calls` | no | A turn took more than six model calls |
| `never-left-the-start` | no | Ten or more turns, all in one location |

Hard findings are broken rules and fail the playtest. Soft findings are for a person to read: they are how a dull or
confused game shows up, and a model can produce one honestly.

The `Playtest` workflow (`.github/workflows/playtest.yml`) runs a playtest for each model in its matrix once a week
and on request (Actions → Playtest → Run workflow). It needs `OPENAI_API_KEY` and/or `ANTHROPIC_API_KEY` as repository
secrets and skips a provider whose key is absent. Each run's summary and JSON report are attached to it. Add a model to
the matrix before making it a default.
