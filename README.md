# Szork 🧙‍♂️🎮 - AI-Powered Text Adventure Game

**Szork** is an innovative voice-controlled, AI-powered text adventure game that reimagines the classic Zork experience for the modern era. Built with [LLM4S](https://github.com/llm4s/llm4s), it demonstrates the power of combining Large Language Models with real-world tools to create immersive, dynamic gaming experiences.

## 🌟 Features

### Core Gameplay
- 🧠 **AI Dungeon Master**: An LLM agent acts as your DM, creating dynamic narratives and managing game state
- 🎙️ **Voice Control**: Speak your commands naturally (Speech-to-Text integration)
- 🗣️ **Narrated Adventures**: Responses are spoken aloud (Text-to-Speech)
- 🎨 **AI-Generated Scenes**: Each location is illustrated with AI-generated artwork
- 📚 **Ready-Made Adventures**: Keep an adventure to play again, with its pictures and music made once ([docs/ADVENTURES.md](docs/ADVENTURES.md))
- 💬 **Natural Language**: No need to remember specific commands - just speak naturally
- 💾 **Auto-Save**: Game state is automatically saved and can be resumed anytime

### Technical Features
- 🔄 **Hot Reload Development**: Changes to code automatically restart the server
- 🌐 **Web-Based Interface**: Modern Vue.js frontend with WebSocket real-time communication
- 🎭 **Multiple Themes**: Choose from various adventure themes and art styles
- 🏺 **Persistent State**: Game state, inventory, and progress are maintained
- 🎵 **Dynamic Music**: AI-generated background music that adapts to the scene mood
- 🖼️ **Smart Caching**: Images and audio are cached for improved performance

## 🎮 How It Works

1. **Start Your Adventure**: Launch the game and choose your theme
2. **Explore Naturally**: Speak or type commands like "look around", "go north", or "pick up the sword"
3. **Experience the Story**: The AI DM describes scenes, manages inventory, and responds to your actions
4. **See Your World**: AI generates images for each new location you discover
5. **Hear Your Tale**: Text-to-speech narrates the adventure as it unfolds

## 🚀 Quick Start

### Prerequisites
- Java 21+
- sbt 1.12+ (Scala 3.8 is fetched by sbt)
- Node.js 22+ (for the frontend)
- API keys for:
  - OpenAI (Dungeon Master, GPT Image pictures, TTS narration and Whisper voice input)
  - and/or Anthropic (Claude as the Dungeon Master)
  - optionally Replicate (music) or HuggingFace (images)

### Environment Setup

Copy `.env.example` to `.env` and fill in your local values. Never commit `.env`.

```bash
cp .env.example .env
# Edit .env and add your keys (OPENAI_API_KEY/ANTHROPIC_API_KEY, etc.)
```

### Running the Game

#### Backend Server
```bash
# Clone the repository
git clone https://github.com/llm4s/szork.git
cd szork

# Start the server (default port 8090)
sbt szorkStart

# Or with hot reload for development
sbt "~szorkStart"
```

#### Frontend (Optional - for web interface)
```bash
# In a new terminal
cd frontend
npm install
npm run dev
```

Open your browser to http://localhost:3090

### API / Protocol

- Gameplay: WebSocket on port 9002 (typed JSON messages; the Vite dev server proxies `/ws` to it). New and loaded games, commands (streamed), voice commands, and image/music notifications all go through it. A client that reconnects sends `resumeSession` to carry on its game.
- HTTP endpoints (port 8090):
  - `GET /api/health` – health check
  - `GET /api/feature-flags` – capability discovery (LLM/image/music/tts/stt availability)
  - `GET /api/games` – list saved games; `DELETE /api/game/:gameId` – delete one
  - `POST /api/game/validate-theme`, `POST /api/game/generate-adventure` – adventure setup (JSON bodies)
  - `GET /api/adventures`, `POST /api/adventures`, `DELETE /api/adventures/:id` – the adventure library; `GET /api/library/...` – its pictures and music

See `frontend/src/types/WebSocketProtocol.ts` for the client protocol and `src/main/scala/org/llm4s/szork/websocket/protocol/WebSocketProtocol.scala` for the server types.

## 🏗️ Architecture

### Technology Stack
- **Backend**: Scala 3 with the Cask web framework and Java-WebSocket
- **AI Integration**: LLM4S 1.0 — `llm4s-agent` (the Dungeon Master agent: tools, threads, streaming, structured output, step limit, tracing), `llm4s-openai` / `llm4s-anthropic` (providers), `llm4s-image` (scene art), `llm4s-speech` (narration and voice input), `llm4s-observability` (Langfuse tracing)
- **Frontend**: Vue 3 + Vuetify 3 + TypeScript
- **Real-time**: WebSockets for streaming responses
- **Build Tool**: sbt with hot reload support

### Key Components

```
szork/
├── src/main/scala/org/llm4s/szork/
│   ├── api/          # SzorkServer (the server and its HTTP routes), config, sessions, capabilities, engine factory, tracing
│   ├── websocket/    # TypedWebSocketServer and the WebSocket protocol
│   ├── game/         # GameEngine (the llm4s Dungeon Master agent), tools, prompts, output schema, adventure outlines
│   ├── media/        # Image, music, text-to-speech and speech-to-text, media cache
│   ├── persistence/  # Step-based save/load
│   ├── streaming/    # Narration extraction from the streamed JSON answer
│   ├── playtest/     # A whole adventure designed, played and checked against a model
│   └── debug/        # CLI tools to create and step through games
└── frontend/         # Vue 3 / Vuetify web interface
```

### How a turn works
The game is one llm4s agent **thread**: each command is the next turn on it. The Dungeon Master may call the inventory and world-state tools, then answers with a JSON object matching `DungeonMasterOutput.schema` (llm4s validates it and asks the model to correct an invalid answer). Its `narrationText` streams to the player as it is written; the scene drives image and music generation. A scene never moves the player through an exit the world state says is not open: such an answer goes back to the model once (`ExitGuard`). Each turn is saved as a step under `szork-saves/`.

The thread keeps the whole game, but each model call is sent only the system prompt (with the adventure outline) and the most recent conversation that fits the context window: llm4s's `ContextWindowMiddleware` drops the oldest turns, never splits a tool call from its result and always sends the current turn whole. Saves hold the whole thread, so a loaded game is pruned the same way.

## 🛠️ Development

### Hot Reload Development
```bash
# Start with file watching - automatically restarts on code changes
sbt "~szorkStart"
```

### Available Commands
- `sbt szorkStart` - Start the server
- `sbt szorkStop` - Stop the server
- `sbt szorkRestart` - Restart the server
- `sbt szorkStatus` - Check server status
- `sbt compile` - Compile the project
- `sbt test` - Run tests
- `sbt "runMain org.llm4s.szork.playtest.Playtest"` - Design and play a whole adventure against the configured model

### Feature Flags & Per‑Session Overrides

The server exposes capabilities and allows per‑session overrides.

- Backend configuration (env vars):
  - `SZORK_IMAGE_GENERATION_ENABLED`, `SZORK_TTS_ENABLED`, `SZORK_STT_ENABLED`, `SZORK_MUSIC_ENABLED`
- Capability discovery (frontend):
  - `GET /api/feature-flags` → `{ llm, image, music, tts, stt }`
  - The selection screen shows capability chips from this endpoint.
- WebSocket protocol (per‑session):
  - Client → Server `newGame` / `loadGame` (optional overrides): `{ tts?: boolean, stt?: boolean, music?: boolean, image?: boolean }`
  - Server → Client `gameStarted` / `gameLoaded` (effective flags): `{ ttsEnabled, sttEnabled, imageEnabled, musicEnabled }` — a request is limited to what the server can provide
- UI behavior:
  - When a capability is disabled by server, related controls are disabled (e.g., mic button when STT is off, TTS/mute toggle when TTS is off, image/music toggles when disabled).
  - Server errors are shown as a toast and also appear in the chat stream.

### Configuration Options

Set in `.env` (see `.env.example`) or the environment. A blank value counts as not set. A flag (a setting whose
default is `true`) is on unless set to `false`, `off`, `0` or `no`, in any case. The server does not start with a
port it cannot use, an unknown `SZORK_LLM_PROVIDER` or `SZORK_REASONING_EFFORT`, or no LLM; the log says which.
What a client may send is bounded by fixed limits in `SzorkConfig`: a command of 1000 characters, a recording of
10 MB, a theme of 2000 characters, an adventure outline of 256 KB, an HTTP body of 1 MB, a WebSocket message of
16 MB, and three turns running or waiting per game (a command beyond them is refused until one finishes).

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `LLAMA_BASE_URL` | – | LLM provider credentials (OpenAI also powers images, TTS and Whisper). `LLAMA_BASE_URL` is an OpenAI-compatible API used as given, so it ends in `/v1` (default `http://localhost:11434/v1`) |
| `OPENAI_BASE_URL`, `ANTHROPIC_BASE_URL` | `https://api.openai.com/v1`, `https://api.anthropic.com` | Send the Dungeon Master's OpenAI or Anthropic calls elsewhere (a proxy or a compatible server); images, speech and Whisper still use OpenAI's own |
| `SZORK_LLM_PROVIDER`, `SZORK_LLM_MODEL` | from the keys present; `gpt-6-luna` / `claude-sonnet-5-5` | Dungeon Master provider (`openai`, `anthropic` or `llama`) and model (see [Models](#models)) |
| `SZORK_REASONING_EFFORT` | `low` | How hard the adventure designer, the Dungeon Master and its subagents reason before answering, on models that reason: `minimal`, `low`, `medium`, `high`, `xhigh`, `max`, or `default` to leave it to the model. Not sent to a model that does not reason. The Claude 5 models always reason, at `high` unless told otherwise, which is slow and can use up the whole answer |
| `SZORK_DM_CONTEXT_MAX_TOKENS` | `24000` | Conversation tokens sent with each Dungeon Master call, besides the system prompt (oldest turns are dropped first; capped at half the model's context window; `0` or `off` sends the whole game) |
| `SZORK_DM_CONTEXT_MAX_MESSAGES` | – | Limit each call by conversation messages instead of tokens |
| `SZORK_INPUT_GUARDRAIL` | `true` | Check each command with llm4s's prompt-injection guardrail before the Dungeon Master sees it ("ignore your instructions", "print your system prompt"); a refused command gets "The dungeon does not understand you." and changes nothing |
| `SZORK_NPC_AGENT`, `SZORK_HINT_AGENT` | `true`, `true` | The Dungeon Master delegates conversations with characters to an NPC dialogue subagent (given the character's persona from the outline and what the world state records of them), and requests for help to a hint subagent (given the outline and the player's progress), with llm4s's typed subagents. Each costs one extra small model call, plus one Dungeon Master call to use its report, only on the turns that use it |
| `SZORK_SUBAGENT_MODEL` | the Dungeon Master's model | A cheaper or faster model, of the Dungeon Master's provider, for the subagents (e.g. `gpt-4o-mini`) |
| `SZORK_HOST`, `SZORK_PORT`, `SZORK_WS_PORT` | `127.0.0.1`, `8090`, `9002` | Where the HTTP and WebSocket servers listen |
| `SZORK_SESSION_TTL_MINUTES` | `30` | How long a game's session lives with no connection: a client that reconnects within it carries on, after it the game is loaded from its save |
| `SZORK_ALLOWED_ORIGINS` | the dev frontend and this server | Browser origins allowed to open the WebSocket and call the HTTP API (comma-separated); a request with another `Origin` is refused |
| `SZORK_ALLOWED_HOSTS` | – | Names (`name` or `name:port`, comma-separated) the HTTP API answers to besides `localhost`, `127.0.0.1`, `[::1]` and `SZORK_HOST` on `SZORK_PORT`: a request with another `Host` is refused, which defeats DNS rebinding. Set it when the server is reached by another name (a reverse proxy that passes its own `Host`). With `SZORK_HOST=0.0.0.0` the `Host` is checked only if this is set; the server warns at startup whenever `SZORK_HOST` is not a loopback address |
| `SZORK_IMAGE_GENERATION_ENABLED`, `SZORK_IMAGE_PROVIDER`, `SZORK_IMAGE_MODEL` | `true`, `none`, the provider's default | Scene images through the llm4s image client (`openai` for GPT Image, default model `gpt-image-1-mini`; `dalle3`, `dalle2`, `huggingface`, `huggingface-sdxl`, `local`); `SZORK_IMAGE_MODEL` picks another of the provider's models; traced with the Dungeon Master when `TRACING_MODE` is set |
| `SZORK_IMAGE_QUALITY`, `SZORK_IMAGE_PIXELS` | `low`, `1024` | How well GPT Image models draw (`low`, `medium`, `high`, `auto`: cost and time rise steeply), and the longer side in pixels pictures are kept and sent at (as JPEG; click a picture in the game to see it full size) |
| `HUGGINGFACE_API_KEY` (or `HF_API_KEY`, `HUGGINGFACE_TOKEN`) | – | Key for the `huggingface` image providers (the OpenAI ones use `OPENAI_API_KEY`) |
| `STABLE_DIFFUSION_URL` (or `SD_URL`), `STABLE_DIFFUSION_API_KEY` (or `SD_API_KEY`) | `http://localhost:7860`, – | The `local` Stable Diffusion server |
| `SZORK_TTS_ENABLED`, `SZORK_TTS_VOICE`, `SZORK_TTS_MODEL` | `true`, `nova`, `tts-1` | Narration |
| `SZORK_STT_ENABLED`, `SZORK_STT_MODEL` | `true`, `whisper-1` | Voice input |
| `SZORK_MUSIC_ENABLED`, `REPLICATE_API_KEY` | `true`, – | Background music |
| `SZORK_MUSIC_POLL_MAX_ATTEMPTS`, `SZORK_MUSIC_POLL_INTERVAL_MS` | `30`, `1000` | How long to wait for a music track (polls, and the time between them) |
| `SZORK_MEDIA_CONNECT_TIMEOUT_MS`, `SZORK_MEDIA_READ_TIMEOUT_MS` | `10000`, `30000` | Network timeouts for the music provider |
| `SZORK_AUTO_SAVE`, `SZORK_SAVES_DIR` | `true`, `szork-saves` | Saving each turn |
| `SZORK_ADVENTURES_DIR` | `adventures` | The adventure library: ready-made adventures, their pictures and the mood music ([docs/ADVENTURES.md](docs/ADVENTURES.md)) |
| `SZORK_CACHE_ENABLED`, `SZORK_CACHE_DIR`, `SZORK_CACHE_MAX_BYTES`, `SZORK_CACHE_TTL_MS` | `true`, `szork-cache`, 500MB, 7 days | Media cache |
| `TRACING_MODE` (+ `LANGFUSE_*`) | off | Agent and scene image tracing: `console` or `langfuse` |

### Models

Checked with the playtest harness (the OpenAI rows again on llm4s 1.0.0), at the default reasoning effort (`low`):

| Model | Status |
|---|---|
| `claude-sonnet-5-5` (Anthropic default) | Works: designed an adventure in one call and played 30 turns with no finding, about 3.5 s a turn |
| `claude-haiku-5-5` | Works: 20 turns with no rule broken, about 2.7 s a turn; its first outline needed llm4s's one correction |
| `claude-sonnet-4-6` | Works: 12 turns with no finding, but about 11 s a turn, because `low` turns its extended thinking on. Set `SZORK_REASONING_EFFORT=default` to play it without |
| `gpt-6-luna` (OpenAI default) | Works: designed an adventure and played 16 turns with no finding, about 3.7 s a turn. It plays with reasoning off: OpenAI's Chat Completions API lets its current models call tools only that way, and llm4s switches it off for this model |
| `gpt-6.1-terra`, `gpt-5.6-terra`, `gpt-5.6-luna` | Do not work on llm4s 1.0.0: it refuses tools on `gpt-6.1-terra`, and sends the `gpt-5.6` models a reasoning effort OpenAI rejects with tools. All three call tools on Chat Completions when asked directly with reasoning off |
| `gpt-4o` | The previous default; not replayed in the latest live tests |

To check a model, run a playtest on it: `SZORK_LLM_MODEL=<model> sbt "runMain org.llm4s.szork.playtest.Playtest"`
(see [Testing](docs/TESTING.md)).

### Troubleshooting

- **"The AI service rejected the server's API key"**: the key in `.env` is missing, mistyped or for another provider than `SZORK_LLM_PROVIDER`.
- **"The AI service rejected the request; the configured model may not be supported"**: the provider refused the request (often a parameter the model does not take). Set `SZORK_LLM_MODEL` to a model from the table above; the server log has the provider's message.
- **"The AI's adventure design came back incomplete or malformed"**: the model did not finish the outline. Try again; if it repeats, the model may be spending its answer on reasoning: check that `SZORK_REASONING_EFFORT` is not `default`, `high` or above. The server log has the outline's problem.
- **A turn fails with "the turn ended StepLimitReached"**: the Dungeon Master called tools twelve times without answering. The game is unchanged; send the command again. (A call repeated with the same arguments within a turn is not run again, which stops the commonest loop.)
- **The game restarts while you play**: the server is in watch mode (`sbt "~szorkStart"`) and a source file changed. Use `sbt szorkStart` to play.
- **"The model 'dall-e-3' does not exist"**: OpenAI no longer offers DALL-E on the account; set `SZORK_IMAGE_PROVIDER=openai` (GPT Image).
- **No images, music or voice**: `GET /api/feature-flags` shows what the server can provide; each needs its provider's key (see the table above).

## 📚 Documentation

- [Development Guide](docs/README_DEVELOPMENT.md) - Development setup, build and hot reload
- [Testing](docs/TESTING.md) - The test layers, the playtest harness and what CI runs
- [llm4s features](docs/LLM4S_FEATURES.md) - Which llm4s feature is used where, and the workarounds tracked upstream
- [Debug Tools](docs/DEBUG_TOOLS.md) - Create and step through games from the command line
- [Frontend README](frontend/README.md) - Frontend development, features, and configuration
- [Claude Code Guide](CLAUDE.md) - Guide for AI assistants working with this codebase
- [Documentation Index](docs/README.md) - Complete documentation directory

For additional design documents, see the [docs/](docs/) directory.

## 🎤 About

This project was created as a demonstration for the talk **"Scala Meets GenAI: Build the Cool Stuff with LLM4S"**:

- 📅 **Scala Days 2025** - August 21, 2025
- 📍 SwissTech Convention Center, EPFL, Lausanne 🇨🇭
- 🔗 [Talk Details](https://scaladays.org/editions/2025/talks/scala-meets-genai-build-the)
- 🎞️ Slides and talk material: [llm4s/szork-talks](https://github.com/llm4s/szork-talks)

## 👥 Contributors

- **Rory Graves** - [LinkedIn](https://www.linkedin.com/in/roryjgraves/) | [GitHub](https://github.com/rorygraves)
- **Kannupriya Kalra** - [LinkedIn](https://www.linkedin.com/in/kannupriyakalra/) | [GitHub](https://github.com/kannupriyakalra)

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## 🔗 Related Projects

- [LLM4S](https://github.com/llm4s/llm4s) - The Scala toolkit powering Szork's AI capabilities
- [Original Zork](https://github.com/MITDDC/zork) - The classic text adventure that inspired this project

---

*Experience the magic of text adventures reimagined with the power of AI!* 🚀
