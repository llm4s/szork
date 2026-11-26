/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** The game server's WebSocket URL, when it is not proxied at `/ws` on the page's own origin. */
  readonly VITE_WS_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
