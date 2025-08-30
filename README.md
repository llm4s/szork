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
- Java 17+
- Scala 2.13
- sbt 1.8+
- Node.js 18+ (for frontend)
- API Keys for:
  - OpenAI (GPT-4 for DM, DALL-E for images, Whisper for speech)
  - OR Anthropic (Claude for DM)

### Environment Setup

Create a `.env` file in the project root:

```bash
# LLM Provider (choose one)
OPENAI_API_KEY=sk-...
# or
ANTHROPIC_API_KEY=sk-ant-...

# Optional: Configure specific models
SZORK_LLM_PROVIDER=openai  # or anthropic, llama
SZORK_LLM_MODEL=gpt-4o     # or claude-3-sonnet, etc.

# Optional: Image generation
SZORK_IMAGE_PROVIDER=dalle3  # or huggingface, stability
SZORK_IMAGE_ENABLED=true

# Optional: Audio features
SZORK_TTS_ENABLED=true
SZORK_STT_ENABLED=true
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

### Using the API

The game also exposes REST and WebSocket APIs:

```bash
# Start a new game
curl -X POST http://localhost:8090/api/game/start \
  -H "Content-Type: application/json" \
  -d '{"theme": "fantasy", "artStyle": "painting"}'

# Send a command
curl -X POST http://localhost:8090/api/game/{gameId}/command \
  -H "Content-Type: application/json" \
  -d '{"command": "look around"}'
```

WebSocket endpoint: `ws://localhost:9002` for real-time streaming responses.

## 🏗️ Architecture

### Technology Stack
- **Backend**: Scala 2.13 with Cask web framework
- **AI Integration**: LLM4S library for LLM orchestration
- **Frontend**: Vue 3 + Vuetify 3 + TypeScript
- **Real-time**: WebSockets for streaming responses
- **Build Tool**: sbt with hot reload support

### Key Components

```
szork/
├── src/main/scala/org/llm4s/szork/
│   ├── SzorkServer.scala         # Main server with all endpoints
│   ├── GameEngine.scala          # Core game logic and LLM integration
│   ├── StreamingAgent.scala      # Streaming LLM responses
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

### Configuration Options

The game supports extensive configuration through environment variables:

- `SZORK_PORT` - Server port (default: 8090)
- `SZORK_AUTO_SAVE` - Enable auto-save (default: true)
- `SZORK_CACHE_ENABLED` - Enable caching (default: true)
- `SZORK_IMAGE_ENABLED` - Enable image generation (default: true)
- `SZORK_TTS_VOICE` - TTS voice selection
- `SZORK_MUSIC_ENABLED` - Enable background music

## 📚 Documentation

- [Development Guide](README_DEVELOPMENT.md) - Detailed development setup
- [SBT Revolver Guide](README_SBT_REVOLVER.md) - Hot reload configuration
- [Frontend README](frontend/README.md) - Frontend development
- [API Documentation](docs/API.md) - REST and WebSocket API reference

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