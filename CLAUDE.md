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
# Start server with hot reload (recommended for development)
sbt "~szorkStart"

# Standard server operations
sbt szorkStart    # Start server
sbt szorkStop     # Stop server  
sbt szorkRestart  # Restart server
sbt szorkStatus   # Check status

# Build and test
sbt compile       # Compile project
sbt test          # Run tests
```

### Frontend Development  
```bash
cd frontend
npm install       # Install dependencies
npm run dev       # Start dev server (localhost:3090)
npm run build     # Production build
npm run lint      # Lint and fix
npm run lint:check # Lint without fixing (CI)
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

**api/SzorkServer.scala** - `SzorkApp` is the server, built from an injected `SzorkConfig`, LLM client, `Capabilities`
and `EngineFactory` (so tests start a whole server with scripted LLMs): the WebSocket server with separate thread pools
for session work and media, and `SzorkRoutes`, the HTTP API (Cask): feature flags, saved-game list/delete, theme
validation and adventure generation. `object SzorkServer` is the `main` that builds one from the environment.

**api/SzorkConfig.scala** - The one reader of `SZORK_*` settings (`SzorkConfig.load`); everything else is handed the
values it needs. Add a setting there (and to the README table and `.env.example`), not with `EnvLoader` elsewhere.

**websocket/TypedWebSocketServer.scala** - All gameplay. Each `GameSession` runs its work one task at a time on its own
`SerialExecutor`; sessions survive reconnects (`resumeSession`) and expire when idle. Media generation runs on its own
pool and reports `imageReady`/`musicReady` or `mediaFailed`. Refuses browser origins outside `SZORK_ALLOWED_ORIGINS`.
`websocket/protocol/WebSocketProtocol.scala` defines the messages and the `{type, data}` wire format (`ProtocolCodec`);
keep `frontend/src/types/WebSocketProtocol.ts` in sync.

**game/GameEngine.scala** - The Dungeon Master: an llm4s `Agent` built with `Agent.builder` (tools, system prompt,
`withOutput(DungeonMasterOutput.schema)`, `withMaxSteps`, a `ContextWindowMiddleware`, optional `withTracing`). A game is
one agent thread; streaming and blocking agents share one `GraphRuntime`. The middleware trims only what each model call
is sent (`SZORK_DM_CONTEXT_MAX_TOKENS`/`_MESSAGES`); the thread, and so `GameState.agentMessages`, keeps the whole
conversation. Restoring a save seeds a new thread with the saved conversation. **game/DmConversation.scala** owns the
thread, its history and per-turn usage (`runTurn`); **game/SceneMedia.scala** the narration, image and music calls
(see **adventures/** below). A `GuardrailMiddleware` runs
**game/PlayerInputGuardrail.scala** (llm4s's `PromptInjectionDetector` with adventure-safe patterns,
`SZORK_INPUT_GUARDRAIL`) on each command: a blocked turn (`AgentStatus.Blocked`) never reaches the model and is answered
in character with a `refused` `GameResponse` that changes nothing and is not saved.

**game/DungeonMasterOutput.scala** - The structured output schema every turn answers with (narration first, then the
scene or simple response); `imageUpdate` is how the Dungeon Master asks for a new picture of a place that now looks
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
A location the Dungeon Master makes up is recorded with its scene's exits. Completing or failing the `main` quest ends
the adventure (`GameEngine.ending`; the server then sends `gameEnded`, and the player may play on).
Exit states: open = passable; closed = the player can simply open it (unless it has an obstacle); locked/sealed =
impassable until the obstacle is overcome; hidden = unusable until discovered. `OutlineMap.problems` reports an
outline whose states and obstacles do not go together.

**game/ExitGuard.scala** - The rule that a scene never moves the player through an exit that is not open (a move from A
to B is refused when A has exits to B and none is `open`; a place no exit leads to, the opening scene and a look around
are not moves it judges). `ExitGuardMiddleware` (llm4s `wrapModelCall`) checks each answer against the world state as
the model gives it: a refused answer goes back to the model once with the exit, its state and obstacle (neither is
stored in the thread); a second refused move is replaced with a simple "the way is shut" answer. `GameEngine.completeTurn`
checks the rule again before it records a move.

**game/RepeatedToolCalls.scala** - `RepeatedToolCallMiddleware` (llm4s `wrapToolCall`): a tool call the model repeats
with the same arguments within a turn is not run again, and it is told to answer. `ToolRoundsMiddleware`
(`wrapModelCall`): after a turn's first three model calls the model is offered no tools, so a turn that keeps looking
things up answers instead of running out of steps.

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

**adventures/** - `AdventureLibrary`: adventures kept on disk (`adventures/<id>/adventure.json`, SZORK_ADVENTURES_DIR)
to be played again, each location's picture per art style, and one music track per mood (`_music/`). Nothing in it
expires; its media goes to the browser as a URL (`MediaRef.Stored`, served by `/api/library/...`). `BakeAdventure` is
the command that makes an adventure's media ahead of play. A game of a library adventure (`newGame` with
`adventureId`; `GameEngine.playAdventure`, saved as `GameState.adventureId`) skips the designer. **game/SceneMedia.scala**
holds the media rules: a place is drawn once (a library adventure's location from its picture brief - written by
**game/ArtDirector.scala**, a third llm4s agent, when the adventure is kept or baked - into the library; any
other from the scene's `imageDescription`, into the per-game `MediaCache`) and drawn again only when the Dungeon
Master's answer gives `imageUpdate`; music is the mood's one track. `docs/ADVENTURES.md` describes it all.

**persistence/** - Step-based saves under `szork-saves/<game-id>/step-NNNN/`; `GameDirs` validates game ids before they
reach the filesystem. **media/** - image, music, speech and the media cache; `SceneImages` builds the server's one
llm4s image client (traced with the Dungeon Master) and the cached `ImageClient` over it.

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
`WorldInvariantsSpec` plays seeded random games; **playtest/** (`Playtest`, `PlaytestChecks`) is the live harness, also
run weekly by `.github/workflows/playtest.yml`. `docs/TESTING.md` describes the layers. CI
compiles main and test sources with `-Werror` and checks `scalafmtCheckAll`.

## Code Style

- Follow Scala 3 conventions; format with scalafmt
- Errors as values: `SzorkResult[T] = Either[SzorkError, T]` (llm4s calls return `Result[T]`)
- Comprehensive logging with structured messages
- Immutable data structures where possible; state shared across threads must be safe (see `SerialExecutor`)
- Tool calling must be documented in LLM system prompts
