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

The frontend is configured to proxy API requests to `http://localhost:8080/api` for backend communication.

## TODO

- [ ] Connect to backend WebSocket/API for real-time game communication
- [ ] Add game state management with Pinia
- [ ] Implement command history (up/down arrows)
- [ ] Add sound effects and music
- [ ] Create game settings panel
- [ ] Add save/load game functionality