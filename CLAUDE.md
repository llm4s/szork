# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Szork is an AI-powered text adventure game built with Scala 3 and the LLM4S library. It combines classic text adventure gameplay with modern AI features including:

- AI Dungeon Master (an llm4s agent on OpenAI or Anthropic models) for dynamic narratives
- Voice control (Speech-to-Text) and narrated responses (Text-to-Speech) via llm4s-speech
- AI-generated scene artwork (DALL-E, HuggingFace or Stable Diffusion via llm4s-image) and background music (Replicate)
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
- `OPENAI_API_KEY` - OpenAI models, DALL-E, TTS and Whisper
- `ANTHROPIC_API_KEY` - Claude models
- `SZORK_LLM_PROVIDER` / `SZORK_LLM_MODEL` - choose the Dungeon Master's provider and model

## Architecture Overview

### Core Components

**api/SzorkServer.scala** - HTTP server (Cask): feature flags, saved-game list/delete, theme validation and adventure
generation. Starts the WebSocket server with separate thread pools for session work and media.

**websocket/TypedWebSocketServer.scala** - All gameplay. Each `GameSession` runs its work one task at a time on its own
`SerialExecutor`; sessions survive reconnects (`resumeSession`) and expire when idle. Media generation runs on its own
pool and reports `imageReady`/`musicReady` or `mediaFailed`. Refuses browser origins outside `SZORK_ALLOWED_ORIGINS`.
`websocket/protocol/WebSocketProtocol.scala` defines the messages and the `{type, data}` wire format (`ProtocolCodec`);
keep `frontend/src/types/WebSocketProtocol.ts` in sync.

**game/GameEngine.scala** - The Dungeon Master: an llm4s `Agent` built with `Agent.builder` (tools, system prompt,
`withOutput(DungeonMasterOutput.schema)`, `withMaxSteps`, a `ContextWindowMiddleware`, optional `withTracing`). A game is
one agent thread; streaming and blocking agents share one `GraphRuntime`. The middleware trims only what each model call
is sent (`SZORK_DM_CONTEXT_MAX_TOKENS`/`_MESSAGES`); the thread, and so `GameState.agentMessages`, keeps the whole
conversation. Restoring a save seeds a new thread with the saved conversation. A `GuardrailMiddleware` runs
**game/PlayerInputGuardrail.scala** (llm4s's `PromptInjectionDetector` with adventure-safe patterns,
`SZORK_INPUT_GUARDRAIL`) on each command: a blocked turn (`AgentStatus.Blocked`) never reaches the model and is answered
in character with a `refused` `GameResponse` that changes nothing and is not saved.

**game/DungeonMasterOutput.scala** - The structured output schema every turn answers with (narration first, then the
scene or simple response). **game/PromptBuilder.scala** builds the system prompt; **game/GameTools.scala** builds the
inventory tools per game over a `PlayerInventory`.

**game/AdventureGenerator.scala** - Designs a new game's `AdventureOutline` (locations with their planned exits,
interactables, puzzles and secrets; items; characters) with a second llm4s agent, built with
`withOutput(AdventureOutlineSchema.schema)` and no tools; the outline goes into the Dungeon Master's system prompt.

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
RUN_INTEGRATION=true sbt "testOnly *AdventureIntegrationSpec"  # Against a real LLM
cd frontend && npm test                   # Frontend tests
```

Tests script the LLM with the llm4s testkit's `ScriptedLLMClient`: `stubs/ScriptedDm` plays a small cavern and takes
per-test rules, `stubs/DmAnswers` builds structured answers, and `calls`/`lastCall` give the recorded requests. CI
compiles main and test sources with `-Werror` and checks `scalafmtCheckAll`.

## Code Style

- Follow Scala 3 conventions; format with scalafmt
- Errors as values: `SzorkResult[T] = Either[SzorkError, T]` (llm4s calls return `Result[T]`)
- Comprehensive logging with structured messages
- Immutable data structures where possible; state shared across threads must be safe (see `SerialExecutor`)
- Tool calling must be documented in LLM system prompts
