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
- JDK 21 (required by llm4s 0.5)
- sbt 1.10+ (Scala 3.8 is fetched by sbt)
- Node.js 18+ (for frontend)
- API Keys for:
  - OpenAI (GPT-4 for DM, DALL-E for images, Whisper for speech)
  - OR Anthropic (Claude for DM)
  - OR a local [Ollama](https://ollama.com) server with a tool-capable model (`ollama pull llama3.1`)

### Environment Setup

Copy `.env.example` to `.env` and fill in your local values. Never commit `.env`.

```bash
cp .env.example .env
# Edit .env and add your keys (OPENAI_API_KEY/ANTHROPIC_API_KEY, etc.)
```

### LLM configuration

Szork uses [llm4s](https://github.com/llm4s/llm4s) 0.5.1. Each LLM provider is a named section of
`llm4s.providers` in `src/main/resources/application.conf`:

```hocon
llm4s.providers {
  openai    { provider = "openai",    model = "gpt-4o",            apiKey = ${?OPENAI_API_KEY} }
  anthropic { provider = "anthropic", model = "claude-sonnet-4-5", apiKey = ${?ANTHROPIC_API_KEY} }
  ollama    { provider = "ollama",    model = "llama3.1",          baseUrl = "http://localhost:11434" }
}
```

`SZORK_LLM_PROVIDER` (`openai`, `anthropic` or `ollama`) selects a section; when unset, the first provider
whose key (or `LLAMA_BASE_URL`) is present is used. `SZORK_LLM_MODEL` overrides the selected section's model.
Values from `.env` reach these `${?VAR}` bindings too.

### Upgrading from llm4s 0.3.2

- Dependencies moved from `"org.llm4s" %% "core" % "0.3.2"` to the split 0.5.1 artifacts: `llm4s-core`,
  `llm4s-agent`, `llm4s-image` and the provider modules `llm4s-openai`, `llm4s-anthropic` and `llm4s-ollama`
  (all on one `llm4sVersion`). The direct Azure OpenAI, Anthropic SDK and jtokkit dependencies are gone; the
  provider modules bring what they need.
- The hand-built `OpenAIConfig`/`AnthropicConfig` in `SzorkConfig` are replaced by named provider sections in
  `application.conf`, loaded with `Llm4sConfig.provider(name)`. `SZORK_LLM_PROVIDER=llama` now uses the native
  Ollama provider instead of Ollama's OpenAI-compatible endpoint.
- The unused `StreamingAgent` subclass was removed; the game drives the llm4s `Agent` directly.
- See the llm4s [0.4.1 to 0.5.0 upgrade guide](https://github.com/llm4s/llm4s/blob/main/docs/migrations/0-4-1-to-0-5-0.md).

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

- Primary interface: WebSocket `ws://localhost:9002` (typed JSON messages).
- Selected HTTP endpoints remain for utility:
  - `GET /api/health` – health check
  - `GET /api/feature-flags` – capability discovery (LLM/image/music/tts/stt availability)
  - `GET /api/games`, `GET /api/game/list` – list saved games
  - `POST /api/game/save/:sessionId`, `GET /api/game/load/:gameId` – persistence helpers

See `frontend/src/types/WebSocketProtocol.ts` for the client protocol and `src/main/scala/org/llm4s/szork/protocol/WebSocketProtocol.scala` for server types.

## 🏗️ Architecture

### Technology Stack
- **Backend**: Scala 3 with Cask web framework
- **AI Integration**: LLM4S 0.5.1 for LLM orchestration
- **Frontend**: Vue 3 + Vuetify 3 + TypeScript
- **Real-time**: WebSockets for streaming responses
- **Build Tool**: sbt with hot reload support

### Key Components

```
szork/
├── src/main/scala/org/llm4s/szork/
│   ├── SzorkServer.scala         # Main server with all endpoints
│   ├── GameEngine.scala          # Core game logic and LLM integration
│   ├── GameTools.scala           # Tool definitions for the AI agent
│   ├── ImageGeneration.scala     # AI image generation
│   ├── TextToSpeech.scala        # TTS integration
│   ├── MusicGeneration.scala     # Dynamic music generation
│   └── GamePersistence.scala     # Save/load game state
├── frontend/                      # Vue.js web interface
└── talk/                         # Presentation materials
```

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
  - Client → Server `newGame` (optional overrides): `{ tts?: boolean, stt?: boolean, music?: boolean, imageGeneration?: boolean }`
  - Server → Client `gameStarted` (effective flags): `{ ttsEnabled, sttEnabled, imageEnabled, musicEnabled }`
- UI behavior:
  - When a capability is disabled by server, related controls are disabled (e.g., mic button when STT is off, TTS/mute toggle when TTS is off, image/music toggles when disabled).
  - Validation errors from server are surfaced as a toast and also appear in the chat stream.

### Configuration Options

The game supports extensive configuration through environment variables:

- `SZORK_PORT` - Server port (default: 8090)
- `SZORK_AUTO_SAVE` - Enable auto-save (default: true)
- `SZORK_CACHE_ENABLED` - Enable caching (default: true)
- `SZORK_IMAGE_GENERATION_ENABLED` - Enable image generation (default: true)
- `SZORK_TTS_VOICE` - TTS voice selection
- `SZORK_MUSIC_ENABLED` - Enable background music

## 📚 Documentation

- [Development Guide](docs/README_DEVELOPMENT.md) - Detailed development setup and build instructions
- [SBT Revolver Guide](docs/README_SBT_REVOLVER.md) - Hot reload configuration with file watching
- [Frontend README](frontend/README.md) - Frontend development, features, and configuration
- [Claude Code Guide](CLAUDE.md) - Guide for AI assistants working with this codebase
- [Documentation Index](docs/README.md) - Complete documentation directory and archive

For additional design documents and historical information, see the [docs/](docs/) directory.

## 🎤 About

This project was created as a demonstration for the talk **"Scala Meets GenAI: Build the Cool Stuff with LLM4S"**:

- 📅 **Scala Days 2025** - August 21, 2025
- 📍 SwissTech Convention Center, EPFL, Lausanne 🇨🇭
- 🔗 [Talk Details](https://scaladays.org/editions/2025/talks/scala-meets-genai-build-the)

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
