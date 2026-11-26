# Szork Development Guide

Szork is a standalone Scala 3 project built on llm4s 1.0, with a Vue 3 frontend in `frontend/`.
[CONTRIBUTING.md](../CONTRIBUTING.md) has the build and test commands CI runs.

## Running

```bash
./dev.sh                  # the server in watch mode and the frontend together; Ctrl+C stops both
```

Or each on its own:

```bash
sbt szorkStart            # the server (sbt "runMain org.llm4s.szork.api.SzorkServer" runs it in the foreground)
sbt szorkStop             # also szorkRestart, szorkStatus
cd frontend && npm install && npm run dev   # the frontend on http://localhost:3090
```

The frontend's dev server proxies `/api` to the server's HTTP port (8090) and `/ws` to its WebSocket port (9002).

## Hot reload

`sbt "~szorkStart"` runs the server with [sbt-revolver](https://github.com/spray/sbt-revolver) and restarts it when a
Scala file under `src/` changes (Ctrl+C stops watching); a change that does not compile prints the error and the
watch carries on. The aliases are `szorkStart`, `szorkStop`, `szorkRestart` and `szorkStatus`, for revolver's
`reStart`, `reStop`, `reRestart` and `reStatus`. The server runs with `-Xmx1g -XX:MaxMetaspaceSize=512m` on ports 8090
(HTTP) and 9002 (WebSocket), bound to 127.0.0.1 unless `SZORK_HOST` is set.

A restart ends the live sessions: a game in the browser is loaded again from its last saved step. Do not run a
playtest against a server in watch mode while editing.

If the server does not start, read the end of its log: it names a configuration it cannot use (no LLM key, an unknown
provider or reasoning effort, a bad port). Otherwise check that ports 8090 and 9002 are free and that `sbt compile`
succeeds (warnings are errors).

## IntelliJ IDEA

The project imports as an sbt project. If it does not resolve: File, Invalidate Caches and Restart; then reload the
sbt project (View, Tool Windows, sbt, Reload).

## Testing

```bash
sbt test                                   # backend tests (no network; scripted models drive the engine)
sbt "testOnly *WebSocketServerSpec"        # one suite
sbt "runMain org.llm4s.szork.playtest.Playtest"   # a whole adventure against the configured model
cd frontend && npm test                    # frontend tests (Vitest)
```

[TESTING.md](TESTING.md) describes the test layers, the playtest and its checks, and what CI runs.

## Architecture

See the main [README](../README.md#how-it-works) for a diagram, how a turn works and the package layout, and
[CLAUDE.md](../CLAUDE.md) for each part in detail.

## Dependencies

- **llm4s 1.0**: `llm4s-agent`, `llm4s-openai`, `llm4s-anthropic`, `llm4s-image`, `llm4s-speech`,
  `llm4s-schema-derivation`, `llm4s-observability`, and `llm4s-testkit` for tests
- **Cask** on Undertow: the HTTP API
- **Java-WebSocket**: the game's WebSocket server
- **requests**: Replicate calls for music
- **dotenv-java**: reading `.env`
- **Vue 3, Vuetify, TypeScript, Vite**: the frontend
