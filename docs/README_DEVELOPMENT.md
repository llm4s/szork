# Szork Development Guide

## Build Configuration

Szork is a standalone Scala 3 project built on the LLM4S library (0.6) for its AI features.

## Building and Running

```bash
# Compile
sbt compile

# Run the full server (requires LLM configuration)
sbt "runMain org.llm4s.szork.api.SzorkServer"

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
   cd frontend
   npm install
   npm run dev
   ```

The frontend dev server runs on http://localhost:3090 and proxies API calls to the backend on port 8090.

## Testing

```bash
sbt test                                   # Backend tests (no network; a fake LLM drives the engine)
sbt "testOnly *WebSocketServerSpec"        # One suite
RUN_INTEGRATION=true sbt "testOnly *AdventureIntegrationSpec"   # 20 turns against a real LLM
cd frontend && npm test                    # Frontend tests (Vitest)
```

CI also compiles main and test sources with `-Werror`, checks formatting (`sbt scalafmtCheckAll`) and runs `npm run lint:check`.

## Architecture

See the main [README](../README.md#architecture) for the package layout and how a turn works.

## Dependencies

- **LLM4S 0.6**: `llm4s-agent`, `llm4s-openai`, `llm4s-anthropic`, `llm4s-image`, `llm4s-speech`, `llm4s-observability`
- **Cask**: HTTP server
- **Java-WebSocket**: WebSocket server
- **Vue 3 / Vuetify 3**: frontend
