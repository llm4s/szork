# Szork 🧙‍♂️🎮 - AI-Powered Text Adventure Game

**Szork** is an innovative voice-controlled, AI-powered text adventure game that reimagines the classic Zork experience for the modern era. Built with [LLM4S](https://github.com/llm4s/llm4s), it demonstrates the power of combining Large Language Models with real-world tools to create immersive, dynamic gaming experiences.

## 🌟 Features

### Core Gameplay
- 🧠 **AI Dungeon Master**: An LLM agent acts as your DM, creating dynamic narratives and managing game state
- 🎙️ **Voice Control**: Speak your commands naturally (Speech-to-Text integration)
- 🗣️ **Narrated Adventures**: Responses are spoken aloud (Text-to-Speech)
- 🎨 **AI-Generated Scenes**: Each location is illustrated with AI-generated artwork
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
  - OpenAI (Dungeon Master, DALL-E images, TTS narration and Whisper voice input)
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

See `frontend/src/types/WebSocketProtocol.ts` for the client protocol and `src/main/scala/org/llm4s/szork/websocket/protocol/WebSocketProtocol.scala` for the server types.

## 🏗️ Architecture

### Technology Stack
- **Backend**: Scala 3 with the Cask web framework and Java-WebSocket
- **AI Integration**: LLM4S 0.6 — `llm4s-agent` (the Dungeon Master agent: tools, threads, streaming, structured output, step limit, tracing), `llm4s-openai` / `llm4s-anthropic` (providers), `llm4s-image` (scene art), `llm4s-speech` (narration and voice input), `llm4s-observability` (Langfuse tracing)
- **Frontend**: Vue 3 + Vuetify 3 + TypeScript
- **Real-time**: WebSockets for streaming responses
- **Build Tool**: sbt with hot reload support

### Key Components

```
szork/
├── src/main/scala/org/llm4s/szork/
│   ├── api/          # SzorkServer (HTTP), config, sessions, capabilities, engine factory, tracing
│   ├── websocket/    # TypedWebSocketServer and the WebSocket protocol
│   ├── game/         # GameEngine (the llm4s Dungeon Master agent), tools, prompts, output schema, adventure outlines
│   ├── media/        # Image, music, text-to-speech and speech-to-text, media cache
│   ├── persistence/  # Step-based save/load
│   ├── streaming/    # Narration extraction from the streamed JSON answer
│   └── debug/        # CLI tools to create and step through games
└── frontend/         # Vue 3 / Vuetify web interface
```

### How a turn works
The game is one llm4s agent **thread**: each command is the next turn on it. The Dungeon Master may call the inventory tools, then answers with a JSON object matching `DungeonMasterOutput.schema` (llm4s validates it and asks the model to correct an invalid answer). Its `narrationText` streams to the player as it is written; the scene drives image and music generation. Each turn is saved as a step under `szork-saves/`.

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

Set in `.env` (see `.env.example`) or the environment:

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `LLAMA_BASE_URL` | – | LLM provider credentials (OpenAI also powers DALL-E, TTS and Whisper) |
| `SZORK_LLM_PROVIDER`, `SZORK_LLM_MODEL` | from the keys present; `gpt-4o` / `claude-sonnet-5-5` | Dungeon Master provider and model |
| `SZORK_DM_CONTEXT_MAX_TOKENS` | `24000` | Conversation tokens sent with each Dungeon Master call, besides the system prompt (oldest turns are dropped first; capped at half the model's context window; `0` or `off` sends the whole game) |
| `SZORK_DM_CONTEXT_MAX_MESSAGES` | – | Limit each call by conversation messages instead of tokens |
| `SZORK_INPUT_GUARDRAIL` | `true` | Check each command with llm4s's prompt-injection guardrail before the Dungeon Master sees it ("ignore your instructions", "print your system prompt"); a refused command gets "The dungeon does not understand you." and changes nothing (`false` or `off` disables) |
| `SZORK_HOST`, `SZORK_PORT`, `SZORK_WS_PORT` | `127.0.0.1`, `8090`, `9002` | Where the HTTP and WebSocket servers listen |
| `SZORK_ALLOWED_ORIGINS` | the dev frontend and this server | Browser origins allowed to open the WebSocket (comma-separated) |
| `SZORK_IMAGE_GENERATION_ENABLED`, `SZORK_IMAGE_PROVIDER` | `true`, `none` | Scene images through the llm4s image client (`dalle3`, `dalle2`, `huggingface`, `huggingface-sdxl`, `local`); traced with the Dungeon Master when `TRACING_MODE` is set |
| `HUGGINGFACE_API_KEY` (or `HF_API_KEY`, `HUGGINGFACE_TOKEN`) | – | Key for the `huggingface` image providers (the DALL-E ones use `OPENAI_API_KEY`) |
| `STABLE_DIFFUSION_URL` (or `SD_URL`), `STABLE_DIFFUSION_API_KEY` (or `SD_API_KEY`) | `http://localhost:7860`, – | The `local` Stable Diffusion server |
| `SZORK_TTS_ENABLED`, `SZORK_TTS_VOICE`, `SZORK_TTS_MODEL` | `true`, `nova`, `tts-1` | Narration |
| `SZORK_STT_ENABLED`, `SZORK_STT_MODEL` | `true`, `whisper-1` | Voice input |
| `SZORK_MUSIC_ENABLED`, `REPLICATE_API_KEY` | `true`, – | Background music |
| `SZORK_AUTO_SAVE`, `SZORK_SAVES_DIR` | `true`, `szork-saves` | Saving each turn |
| `SZORK_CACHE_ENABLED`, `SZORK_CACHE_DIR`, `SZORK_CACHE_MAX_BYTES`, `SZORK_CACHE_TTL_MS` | `true`, `szork-cache`, 500MB, 7 days | Media cache |
| `TRACING_MODE` (+ `LANGFUSE_*`) | off | Agent and scene image tracing: `console` or `langfuse` |

## 📚 Documentation

- [Development Guide](docs/README_DEVELOPMENT.md) - Detailed development setup and build instructions
- [SBT Revolver Guide](docs/README_SBT_REVOLVER.md) - Hot reload configuration with file watching
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
