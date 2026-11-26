# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Szork is an AI-powered text adventure game built with Scala 3 and the LLM4S library. It combines classic text adventure gameplay with modern AI features including:

- AI Dungeon Master (an llm4s agent on OpenAI or Anthropic models) for dynamic narratives
- Voice control (Speech-to-Text) and narrated responses (Text-to-Speech) via llm4s-speech
- AI-generated scene artwork (OpenAI GPT Image or DALL-E, HuggingFace or Stable Diffusion via llm4s-image) and background music (Replicate)
- WebSocket-based real-time communication
- Vue.js frontend with Vuetify UI components
- Persistent game state with save/load functionality

## Essential Commands

### Backend Development
```bash
./dev.sh          # Backend (watch mode) and frontend together; Ctrl+C stops both

# Start server with hot reload (recommended for development)
sbt "~szorkStart"

# Standard server operations
sbt szorkStart    # Start server
sbt szorkStop     # Stop server  
sbt szorkRestart  # Restart server
sbt szorkStatus   # Check status

# Build and test (warnings are errors: -Werror is in build.sbt)
sbt compile       # Compile project
sbt test          # Run tests
sbt scalafmtCheckAll Test/compile test   # What CI runs (sbt scalafmtAll fixes formatting)
```

### Frontend Development  
```bash
cd frontend
npm install       # Install dependencies
npm run dev       # Start dev server (localhost:3090)
npm run build     # Production build
npm run lint      # Lint and fix
npm run lint:check # Lint without fixing (CI)
npm run typecheck # vue-tsc (build runs it too)
npm test          # Vitest tests
npm run format    # Format code
```

### Environment Setup
Copy `.env.example` to `.env` and fill in keys (the README has the full variable table):
- `OPENAI_API_KEY` - OpenAI models, images (`SZORK_IMAGE_PROVIDER=openai`), TTS and Whisper
- `ANTHROPIC_API_KEY` - Claude models
- `SZORK_LLM_PROVIDER` / `SZORK_LLM_MODEL` - choose the Dungeon Master's provider and model (Anthropic default:
  `claude-sonnet-5-5`); `SZORK_REASONING_EFFORT` (default `low`) - how hard a reasoning model thinks; the Claude 5 models
  cannot finish an outline at their own default

## Architecture Overview

### Core Components

**api/SzorkApp.scala** - `SzorkApp` is the server, built from an injected `SzorkConfig`, LLM client, `Capabilities`
and `EngineFactory` (so tests start a whole server with scripted LLMs): the WebSocket server with separate thread pools
for session work, images and music, and the HTTP API on Undertow. Every HTTP request first passes `SzorkApp.refusal`:
its `Host` must be a loopback name or `SZORK_HOST` on `SZORK_PORT`, or one `SZORK_ALLOWED_HOSTS` adds (DNS rebinding);
an `Origin`, if it has one, must be in `SZORK_ALLOWED_ORIGINS`; its body at most 1 MB. **api/SzorkRoutes.scala** - the
Cask routes: feature flags, saved-game list/delete, the adventure library, theme validation (**api/ThemeValidator.scala**)
and adventure generation. **api/GameChoices.scala** - `GameTheme` and `ArtStyle`. **api/SzorkServer.scala** - the `main`:
it loads the config, refuses to start (exit 1, reasons in the log) on an unusable port, an unknown
`SZORK_LLM_PROVIDER` or `SZORK_REASONING_EFFORT`, or no LLM (`SzorkConfig.validate`), and starts `SzorkApp.fromConfig`.

**api/SzorkConfig.scala** - The one reader of `SZORK_*` settings (`SzorkConfig.load`); everything else is handed the
values it needs. Add a setting there (and to the README table and `.env.example`), not with `EnvLoader` elsewhere. Flags
use `SzorkConfig.flag` (off for `false`, `off`, `0`, `no`); blank values are unset. It also holds the fixed limits on
client input (`MaxCommandChars`, `MaxAudioBytes`, `MaxThemeChars`, `MaxOutlineChars`, `MaxRequestBytes`,
`MaxMessageBytes`, and `MaxPendingTurns` per session). **config/EnvLoader.scala** (`org.llm4s.szork.config`) reads
`.env` from the working directory or its parent; the process environment wins over it.

**websocket/TypedWebSocketServer.scala** - All gameplay. Each `GameSession` runs its work one task at a time on its own
`SerialExecutor`; sessions survive reconnects (`resumeSession`; `sessionResumed` says `turnInProgress`) and expire when
idle. A session that ends (expired, or its game deleted - its player gets `gameDeleted`) drops the work still queued on
it. A command beyond `MaxPendingTurns` running or waiting is refused. Media generation runs on its own pools and reports
`imageReady`/`musicReady` or `mediaFailed`. Refuses browser origins outside `SZORK_ALLOWED_ORIGINS`. Errors are logged
with `message` and sent to the player as `userMessage` (**error/SzorkError.scala**), never a provider's text or a path.
`websocket/protocol/WebSocketProtocol.scala` defines the messages and the `{type, data}` wire format (`ProtocolCodec`);
keep `frontend/src/types/WebSocketProtocol.ts` in sync.

**game/GameEngine.scala** - The Dungeon Master: an llm4s `Agent` built with `Agent.builder` (tools, system prompt,
`withOutput(DungeonMasterOutput.schema)`, `withOutputCheck(GameResponseValidator.problems)`, `withMaxSteps`, middleware,
a `ContextWindowMiddleware`, optional `withTracing`). A turn is whole or nothing (`playTurn`): one that does not complete
(fails, ends at a step or output limit, or breaks the exit rule) restores the world and inventory and leaves the
conversation as it was; a completed one is decoded once with `decodeOutput`. A game is
one agent thread; streaming and blocking agents share one `GraphRuntime`. The middleware trims only what each model call
is sent (`SZORK_DM_CONTEXT_MAX_TOKENS`/`_MESSAGES`); the thread, and so `GameState.agentMessages`, keeps the whole
conversation. Restoring a save seeds a new thread with the saved conversation. **game/DmConversation.scala** owns the
thread, its history and per-turn usage (`runTurn`); **game/SceneMedia.scala** the narration, image and music calls
(see **adventures/** below). A `GuardrailMiddleware` runs
**game/PlayerInputGuardrail.scala** (llm4s's `PromptInjectionDetector` with adventure-safe patterns,
`SZORK_INPUT_GUARDRAIL`) on each command: a blocked turn (`AgentStatus.Blocked`) never reaches the model and is answered
in character with a `refused` `GameResponse` that changes nothing and is not saved.

**game/DungeonMasterOutput.scala** - The answer every turn gives: the case class `Answer` (narration first; a
`fullScene` or a `simple` response in one object, since llm4s requires an object at the root), with its schema derived
by `SchemaOf` (`@description` must be one string literal). **game/GameResponseValidator.scala** - the rules the schema
cannot say (ids, non-blank text, the fields each kind needs), run as llm4s's output check, and `read` into game data; `imageUpdate` is how the Dungeon Master asks for a new picture of a place that now looks
different. A simple response may give `items`/`npcs` when the action changed what is here; the engine
keeps the current scene (sent with every response) up to date with them. **game/PromptBuilder.scala** builds the system prompt; **game/WorldStateMiddleware.scala**
adds the current game state (location, exits, inventory, active quests) to every model call as an llm4s prompt section,
never stored in the thread; **game/GameTools.scala** builds the
tools per game: inventory tools over a `PlayerInventory`, and world-state tools (`get_location_state`, `set_exit_state`,
`set_location_flag`, `record_npc_met`, `update_quest_progress`, `get_world_summary`) over a `WorldState`. The state
section lists the unfinished quests and the characters the outline places at the player's location and not yet met.

**game/WorldState.scala** - The game's memory beyond the context window: exit states (`ExitState` enum), location
flags, visits, characters met and quest progress. Seeded from the outline (`WorldSnapshot.fromOutline`); scene answers
record visits (there is no move tool; `ExitGuard` holds them to the exit states); saved in `GameState.worldState`.
A location the Dungeon Master makes up is recorded with its scene's exits. Names are matched loosely: items trimmed and
ignoring case (`PlayerInventory` too), a short character name to the one outline character it names as a whole word,
a tool's location name made a lower_snake_case id; the main quest only by its id. Completing or failing the `main`
quest ends the adventure (`GameEngine.ending`; the server then sends `gameEnded`, and the player may play on).
Exit states: open = passable; closed = the player can simply open it (unless it has an obstacle); locked/sealed/blocked
= impassable until the obstacle is overcome; hidden = unusable until discovered. **game/OutlineMap.scala** -
`problems` returns typed `Problem`s (`ProblemKind`: unreachable locations, exits to nowhere, unknown directions or
states, mismatched obstacles, a start with no way out, misplaced characters and items, ...); `repair` drops what it
can. Outline items, like characters, have a location.

**game/ExitGuard.scala** - The rule that a scene never moves the player through an exit that is not open (a move from A
to B is refused when A has exits to B and none is `open`; a place no exit leads to, the opening scene and a look around
are not moves it judges). `ExitGuardMiddleware` (llm4s `wrapModelCall`) checks each answer against the world state as
the model gives it: a refused answer goes back to the model once with the exit, its state and obstacle (neither is
stored in the thread); a second refused move is replaced with a simple "the way is shut" answer. It reads the raw reply
with llm4s's `PartialJson` (`DungeonMasterOutput.answerIn`). `GameEngine.playTurn` checks the rule again and discards a
turn that still breaks it.

**game/RepeatedToolCalls.scala** - `RepeatedToolCallMiddleware` (llm4s `wrapToolCall`): a tool call the model repeats
with the same arguments, with nothing changed since (a successful call of a tool not in `GameTools.ReadOnly` starts
afresh), is not run again, and it is told to answer. `ToolRoundsMiddleware` (`wrapModelCall`): after a turn's first
three model calls (llm4s's output corrections count; the exit guard's correction, which it wraps, does not) the model is
offered no tools, so a turn that keeps looking things up answers instead of running out of steps.

**game/DmSubagents.scala** - The Dungeon Master's llm4s subagents (`TypedAgent`s offered with `withSubagents`, bounded
by `withDelegation(DmSubagents.Delegation)`): `npc_dialogue` (tool `task_npc_dialogue`, returns `NpcLines`) and `hint`
(`task_hint`, returns `Hint`). Their briefs are rendered when the task runs (`withInputText`) from the outline, world
state and inventory; each has no tools, one step and a token/time `TaskBudget`. The DM stays in charge and weaves the
report into its answer; subagents do not stream (turns stream without `nested`) and trace under the DM's run.
Configured by `SubagentConfig` (SZORK_NPC_AGENT, SZORK_HINT_AGENT, SZORK_SUBAGENT_MODEL) via `EngineFactory`; tests
script them in `DmSubagentsSpec` by matching the subagent's system prompt.

**game/AdventureGenerator.scala** - Designs a new game's `AdventureOutline` (locations with their planned exits,
interactables, puzzles and secrets; items; characters, each with the location where they are first met) with a second
llm4s agent, built with
`withOutput(AdventureOutlineSchema.schema)` and no tools; the outline goes into the Dungeon Master's system prompt.
It and **game/ArtDirector.scala** are run by **game/StructuredAgent.scala** (one step; a `Truncated` answer is not
decoded).

**adventures/** - `AdventureLibrary`: adventures kept on disk (`adventures/<id>/adventure.json`, SZORK_ADVENTURES_DIR)
to be played again, each location's picture per art style, and one music track per mood (`_music/`). Nothing in it
expires; its media goes to the browser as a URL (`MediaRef.Stored`, served by `/api/library/...`). `BakeAdventure` is
the command that makes an adventure's media ahead of play. A game of a library adventure (`newGame` with
`adventureId`; `GameEngine.playAdventure`, saved as `GameState.adventureId`) skips the designer. **game/SceneMedia.scala**
holds the media rules: a place is drawn once (a library adventure's location from its picture brief - written by
**game/ArtDirector.scala**, a third llm4s agent, when the adventure is kept or baked - into the library; any
other from the scene's `imageDescription`, into the per-game `MediaCache`) and drawn again only when the Dungeon
Master's answer gives `imageUpdate`; music is the mood's one track, made once (games that need a missing track or
picture at the same time share one generation). `docs/ADVENTURES.md` describes it all.

**persistence/** - Step-based saves under `szork-saves/<game-id>/step-NNNN/`; `GameDirs` validates game ids before they
reach the filesystem; a step is written whole into a new directory, and a game loads from its highest readable step.
**media/** - image, music, speech and the media cache; `SceneImages` builds the server's one llm4s image client (traced
with the Dungeon Master) and the cached `ImageClient` over it; speech clients come from llm4s's
`SpeechProviderSelector`; `MusicGeneration` is plain HTTP to Replicate (llm4s has no music API).

**frontend/src/** - `layouts/GameView.vue` (screens: intro, game list, setup, game) built from `components/`
(`IntroScreen`, `GameSelection`, `AdventureSetup` with `ThemePicker`/`StylePicker`/`FeatureToggles`/
`GenerationProgress`, `MessageList`, `CommandInput`, `ConnectionBanner`, `GameHeaderControls`) and `composables/`
(`useWebSocketGame`, `useAdventureDesign`, `useCustomTheme`, `useFeatureFlags`, `useAudioPlayback`, `useVoiceRecorder`);
`services/WebSocketClient.ts` connects to `/ws` on the page's origin (or `VITE_WS_URL`) and reconnects.

## Development Patterns

### Adding New Game Tools
1. Add the tool in `GameTools.forInventory` (or a sibling builder) with `ToolBuilder` and `SafeParameterExtractor`
2. Keep per-game state in an object passed in, not in a global
3. Document when to use it in `PromptBuilder.scala`

### WebSocket Message Handling
1. Add the case class to `websocket/protocol/WebSocketProtocol.scala` and its `ReadWriter.merge` list (client messages
   also go in `ProtocolCodec`'s list); avoid default values on server-message fields (`macroRW` omits them)
2. Handle it in `TypedWebSocketServer.dispatch`, running engine work with `runFor(session)`
3. Mirror it in `frontend/src/types/WebSocketProtocol.ts`; cover it in `WebSocketServerSpec`

### Game State Extensions
1. Update `GameState` and `GameStateCodec` (old save formats do not need to be supported)
2. Modify `getGameState()` and `restoreGameState()` in `GameEngine.scala`
3. Test save/load round trips

## Configuration

- **Ports**: 8090 (HTTP), 9002 (WebSocket), bound to `127.0.0.1` unless `SZORK_HOST` is set
- **Hot Reload**: sbt-revolver (`sbt "~szorkStart"`)
- **Tracing**: `TRACING_MODE=console|langfuse` (off by default)
- **Frontend**: Vue 3 + TypeScript + Vuetify 3, Vite dev server on port 3090 proxying `/api` and `/ws`

## Testing

```bash
sbt test                                  # All backend tests (fake LLM, no network)
sbt "testOnly *GameEngineAgentSpec"       # One suite
sbt "testOnly * -- -z inventory"          # Tests whose name matches
sbt "runMain org.llm4s.szork.playtest.Playtest"   # Design and play a whole adventure on the configured model
sbt "runMain org.llm4s.szork.adventures.BakeAdventure --list"   # The adventure library; see docs/ADVENTURES.md
RUN_INTEGRATION=true sbt "testOnly *AdventureIntegrationSpec"  # The same playtest as a test
cd frontend && npm test                   # Frontend tests
```

Tests script the LLM with the llm4s testkit's `ScriptedLLMClient`: `stubs/ScriptedDm` plays a small cavern and takes
per-test rules, `stubs/DmAnswers` builds structured answers, and `calls`/`lastCall` give the recorded requests.
`stubs/FakeAnthropic` is a local Messages API for asserting on what goes on the wire (`WireFormatSpec`);
`stubs/FakeReplicate` does the same for music (`MusicGenerationSpec`); `WorldInvariantsSpec` plays seeded random games;
**playtest/** (`Playtest`, `PlaytestChecks`) is the live harness, building its games with `EngineFactory.default` from
the same settings as the server, also run weekly by `.github/workflows/playtest.yml`. `docs/TESTING.md` describes the
layers. `build.sbt` compiles with `-Werror` and `-Wunused` always, so a warning fails every compile; CI runs
`scalafmtCheckAll Test/compile test` and the frontend's lint, tests and build. `hooks/install.sh` installs a pre-commit
hook (`scalafmtCheckAll Test/compile`, and the frontend lint when frontend files are staged) and a commit-msg hook that
requires a DCO `Signed-off-by` line (`git commit -s`); see `CONTRIBUTING.md`.

## Code Style

- Follow Scala 3 conventions; format with scalafmt
- Errors as values: `SzorkResult[T] = Either[SzorkError, T]` (llm4s calls return `Result[T]`)
- Comprehensive logging with structured messages
- Immutable data structures where possible; state shared across threads must be safe (see `SerialExecutor`)
- Tool calling must be documented in LLM system prompts
