# Szork Development Guide

## Build Configuration

Szork is a standalone Scala 2.13 project that depends on the LLM4S library for AI integration.

## Building and Running

```bash
# Compile
sbt compile

# Run the full server (requires LLM configuration)
sbt "runMain org.llm4s.szork.SzorkServer"

# Or use the convenient aliases:
sbt szorkStart    # Start the server
sbt szorkStop     # Stop the server
sbt szorkRestart  # Restart the server
sbt szorkStatus   # Check status

# For hot reload development (recommended):
sbt "~szorkStart"  # Auto-restarts on file changes
```

## IntelliJ IDEA Setup

The szork module should import correctly in IntelliJ. If you encounter any issues:

1. File → Invalidate Caches and Restart
2. After restart, let IntelliJ re-index the project
3. If needed, refresh the sbt project (View → Tool Windows → sbt → Reload)

## Frontend Development

The frontend is in the `frontend` directory. To develop:

1. Terminal 1: Run the backend server (see above)
2. Terminal 2: 
   ```bash
   cd szork/frontend
   npm install
   npm run dev
   ```

The frontend dev server runs on http://localhost:3090 and proxies API calls to the backend on port 8090.

## Architecture

- `SzorkServer.scala` - Full game server with LLM integration
- `SzorkSimpleServer.scala` - Simple test server without LLM
- `frontend/` - Vue.js/Vuetify frontend application

## Dependencies

Szork is a standalone project that uses:
- **LLM4S v0.1.11**: For LLM orchestration and agent functionality
- **Cask**: Web framework for HTTP server
- **Java-WebSocket**: For real-time communication
- **Various AI SDKs**: OpenAI, Anthropic for LLM integration