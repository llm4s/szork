# Szork Frontend

The browser game: Vue 3, Vuetify and TypeScript, built with Vite. It is the only way to play Szork; it needs the game
server running (see the main [README](../README.md#quick-start)).

## Scripts

```bash
npm install          # or npm ci, as CI does
npm run dev          # the dev server on http://localhost:3090, proxying /api and /ws to the game server
npm run build        # type-check (vue-tsc) and build into dist/
npm run preview      # serve the build, with the same proxy
npm run lint         # ESLint, fixing what it can
npm run lint:check   # ESLint, failing on any warning (CI)
npm test             # Vitest
npm run format       # Prettier
```

CI runs `npm ci`, `npm run lint:check`, `npm test` and `npm run build`.

## Structure

```
src/
  main.ts, App.vue          the app, Vuetify (plugins/) and the shared styles (styles/)
  router/                   / and /game/:gameId, both GameView (the second loads that saved game)
  layouts/GameView.vue      the screens - intro, game list, adventure setup, the game - and the game itself
  components/
    IntroScreen             the title screen
    GameSelection           ready-made adventures, saved games (load, delete), the server's features
    AdventureSetup          a new adventure: ThemePicker, StylePicker, FeatureToggles, GenerationProgress
    MessageList             the transcript: narration, pictures, exits
    CommandInput            the command line and push-to-talk microphone
    GameHeaderControls      back, and the picture, music and narration switches
    ConnectionBanner        reconnecting, or Retry once the client has given up
  composables/
    useWebSocketGame        the game over the WebSocket: start, load, commands, streaming, media, resume
    useAdventureDesign      designing an adventure, and keeping it in the library
    useCustomTheme          a custom theme and its validation
    useFeatureFlags         what the server can provide
    useAudioPlayback        narration and background music
    useVoiceRecorder        recording a voice command
  services/WebSocketClient.ts   the connection: its state, reconnection, a queue of messages sent while disconnected, and a ping
  types/                    WebSocketProtocol.ts (mirrors the server's protocol) and api.ts (HTTP responses)
  data/                     the themes and art styles offered, and the server's input limits (limits.ts)
  test/                     a fake WebSocket and a Vuetify mount helper for the specs
```

Specs sit beside what they test (`*.spec.ts`); `App.spec.ts` mounts the whole app.

## What talks to what

**HTTP**, through the `/api` proxy to the server's port 8090:

| Route | Used by |
|---|---|
| `GET /api/feature-flags` | `useFeatureFlags`: which of pictures, music, narration and voice input the server can provide |
| `GET /api/games`, `DELETE /api/game/:gameId` | `GameSelection`: the saved games |
| `GET /api/adventures` | `GameSelection`: the ready-made adventures |
| `POST /api/game/validate-theme` | `useCustomTheme` |
| `POST /api/game/generate-adventure` | `useAdventureDesign`: design an outline |
| `POST /api/adventures` | `useAdventureDesign`: keep a designed adventure in the library |
| `GET /api/library/...` | `<img>` and `<audio>`: a library adventure's pictures and the mood music |

The server also has `GET /api/health` and `DELETE /api/adventures/:id`, which the frontend does not use.

**WebSocket**, to `/ws` on the page's own origin, which Vite proxies to the server's port 9002. Set `VITE_WS_URL`
(for example `ws://localhost:9002`) to connect elsewhere. A page served from an origin other than the dev server's
must be added to the server's `SZORK_ALLOWED_ORIGINS`. Every message is `{type, data}`; the types are in
[`types/WebSocketProtocol.ts`](src/types/WebSocketProtocol.ts), kept in step with the server's
`WebSocketProtocol.scala`.

- The browser sends `newGame` (a theme and its display name, an art style, a designed outline or a library
  `adventureId`, and the features wanted), `loadGame`, `streamCommand`, `audioCommand` (a recording, with its
  `mimeType`), `resumeSession` and `ping`.
- The server answers with `gameStarted` / `gameLoaded` (with the title and the features granted), `textChunk` as the
  narration is written, then `streamComplete`; `transcription` for a voice command; `imageReady`, `musicReady` or
  `mediaFailed` when media is done; `gameEnded` when the main quest is won or lost; `sessionResumed`,
  `sessionTakenOver` and `gameDeleted`; and `error`.

## Reconnecting

When the connection drops, the client reconnects (up to five attempts, with a growing delay) and sends
`resumeSession`. The server's `sessionResumed` says whether a turn is still in progress; if not, and no answer was
missed, the command is put back in the command line. If the session has expired (the server was restarted, or
`SZORK_SESSION_TTL_MINUTES` passed), the game is loaded from its save and a command it never answered is sent again. A
turn that hears nothing for two minutes is ended with an error. `ConnectionBanner` shows the state, with Retry once
the client has given up.
