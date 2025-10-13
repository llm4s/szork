# Szork Frontend

A Vue 3 + Vuetify 3 + TypeScript frontend for the Szork text adventure game.

## Development Setup

```bash
# Install dependencies
npm install

# Run development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Run linting
npm run lint

# Format code
npm run format
```

## Features

- Vue 3 with Composition API
- Vuetify 3 for Material Design components
- TypeScript for type safety
- Vite for fast development and building
- Dark theme optimized for text adventure gameplay
- Single-page application with Vue Router

## Structure

- `/src/layouts/GameView.vue` - Main game interface component
- `/src/router/` - Vue Router configuration
- `/src/plugins/` - Vuetify and other plugin configurations

## API Integration

The frontend communicates with the backend via:
- **WebSocket** (primary): Real-time game communication on port 9002
- **REST API**: Feature flags and capability discovery

## Current Features

✅ **Implemented:**
- Real-time WebSocket communication with backend
- Streaming text responses with visual feedback
- Image generation with loading states
- Background music generation and playback
- Voice narration (TTS) with playback controls
- Speech-to-text command input
- Game save/load functionality
- Feature flag discovery and capability checks
- Adventure theme and art style selection
- Per-session feature overrides (TTS, STT, music, images)

🚧 **Potential Future Enhancements:**
- Command history navigation (up/down arrows)
- Advanced game settings panel
- Multiple voice options for narration
- Ambient sound effects
- Save game management UI

## Configuration

- WebSocket URL can be configured via `VITE_WS_URL` (e.g. `ws://localhost:9002`)
- In dev, the app connects to `ws://localhost:3090/ws`, which Vite proxies to `ws://localhost:9002`
- If neither is set, it defaults to `ws://<host>:9002`
- Feature flags are fetched from `/api/feature-flags` on startup
