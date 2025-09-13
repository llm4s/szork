# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Szork is an AI-powered text adventure game built with Scala 2.13 and the LLM4S library. It combines classic text adventure gameplay with modern AI features including:

- AI Dungeon Master using GPT-4/Claude for dynamic narratives
- Voice control (Speech-to-Text) and narrated responses (Text-to-Speech) 
- AI-generated scene artwork using DALL-E
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
npm run lint      # Lint code
npm run format    # Format code
```

### Environment Setup
Copy `.env` file with required API keys:
- `OPENAI_API_KEY` - For GPT-4, DALL-E, Whisper
- `ANTHROPIC_API_KEY` - For Claude models  
- `LLM_MODEL` - Specify model (e.g., "openai/gpt-4o")

## Architecture Overview

### Core Components

**SzorkServer.scala** - Main HTTP/WebSocket server using Cask framework
- REST API endpoints for game management
- WebSocket server on port 9002 for real-time communication
- Session management and game persistence
- Configuration validation and LLM client initialization

**GameEngine.scala** - Core game logic and AI integration
- LLM4S Agent orchestration with tool calling
- Streaming and non-streaming text generation
- Scene parsing and game state management
- Audio/image generation coordination
- Complete game state persistence and restoration

**TypedWebSocketServer.scala** - Type-safe WebSocket communication
- Protocol-based message handling using case classes
- Real-time streaming responses
- Image/music generation coordination
- Session-based connection management

**GameTools.scala** - Tool functions for LLM agent
- Inventory management (list/add/remove items)
- Tool registry for LLM4S integration
- Persistent state for game saves/loads

### Data Models

**GameModels.scala** defines the core game state structures:
- `GameScene` - Complete scene with location, exits, items, NPCs
- `SimpleResponse` - Simple actions without scene changes
- `Exit` - Navigation between locations
- Streaming response parsing for real-time gameplay

### Key Features

**Streaming Architecture** - Text responses stream in real-time via WebSocket, with JSON parsing to extract narrative text separately from structured game data.

**Tool Integration** - LLM agent has access to inventory management tools and must use them consistently for game state tracking.

**Multi-modal Generation** - Coordinates text, audio, and image generation asynchronously with caching to improve performance.

**Game Persistence** - Complete agent state serialization including conversation history, tool calls, and media cache.

## Development Patterns

### Adding New Game Tools
1. Define tool schema in `GameTools.scala`
2. Implement handler function with `SafeParameterExtractor`
3. Add tool to `allTools` sequence
4. Update game prompt in `GameEngine.scala` to document tool usage

### WebSocket Message Handling
1. Add case class to `protocol/WebSocketProtocol.scala`
2. Add parsing logic in `TypedWebSocketServer.parseClientMessage()`
3. Implement handler method in `TypedWebSocketServer.scala`
4. Add logging with consistent format

### Game State Extensions
1. Update `GameState` case class in persistence layer
2. Modify `getGameState()` and `restoreGameState()` in `GameEngine.scala`
3. Handle backwards compatibility for existing saves
4. Test save/load functionality thoroughly

## Configuration

### Server Configuration
- **Port**: Default 8090 (HTTP), 9002 (WebSocket)
- **Hot Reload**: Enabled via sbt-revolver plugin
- **File Watching**: Auto-restart on Scala file changes

### LLM Configuration  
- **Provider**: OpenAI or Anthropic via LLM4S
- **Model**: Configurable via `LLM_MODEL` environment variable
- **Tools**: Inventory management, structured response generation
- **Streaming**: Support for real-time text generation

### Frontend Configuration
- **Framework**: Vue 3 + TypeScript + Vuetify 3
- **Dev Server**: Vite on port 3090  
- **WebSocket**: Connects to backend on port 9002
- **Build**: TypeScript compilation with Vue SFC support

## Testing

```bash
sbt test                           # Run all tests
sbt "testOnly *GameEngineTest"     # Run specific test
sbt "testOnly * -- -z inventory"  # Run tests matching pattern
```

## Code Style

- Follow Scala 2.13 conventions
- Use consistent error handling with `Either[String, T]` 
- Comprehensive logging with structured messages
- Immutable data structures where possible
- Tool calling must be documented in LLM system prompts