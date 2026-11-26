/**
 * WebSocket client for real-time game communication.
 *
 * Manages the connection lifecycle (including automatic reconnection after an unexpected close),
 * queues messages sent while disconnected, and routes server messages to typed handlers.
 */

import type { ClientMessage, ServerMessage, FeatureRequest, NewGameRequest } from "@/types/WebSocketProtocol";

/** The server message with the given `type`. */
export type ServerMessageOf<K extends ServerMessage["type"]> = Extract<ServerMessage, { type: K }>;

/**
 * Where the connection stands: `connecting` to a first connection (or one the player asked for),
 * `open`, `reconnecting` after an unexpected close, or `closed` (closed deliberately, or given up on).
 */
export type ConnectionState = "connecting" | "open" | "reconnecting" | "closed";

/** Called whenever the connection state changes; `reconnected` is true when it opens after an unexpected close. */
export type ConnectionListener = (state: ConnectionState, reconnected: boolean) => void;

/**
 * The WebSocket URL: VITE_WS_URL when it is set, otherwise `/ws` on the page's own origin. The Vite
 * server (dev and preview) proxies `/ws` to the game server's WebSocket port, as it does `/api` to its
 * HTTP port, so the page and both APIs share one origin.
 */
function deriveWsUrl(): string {
  const envWs = import.meta.env.VITE_WS_URL;
  if (envWs && envWs.trim().length > 0) return envWs.trim();
  const { protocol, host } = window.location;
  return `${protocol === "https:" ? "wss:" : "ws:"}//${host}/ws`;
}

export class WebSocketClient {
  private ws: WebSocket | null = null;
  private url: string;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private messageHandlers = new Map<string, Set<(message: ServerMessage) => void>>();
  private connectionListeners = new Set<ConnectionListener>();
  private state: ConnectionState = "closed";
  // The connection being opened, so a second connect() waits for the same one
  private pendingConnect: Promise<void> | null = null;
  // Set by disconnect(): a deliberate close must not trigger reconnection
  private closedIntentionally = false;
  private wasConnected = false;
  private messageQueue: ClientMessage[] = [];
  private pingInterval: ReturnType<typeof setInterval> | null = null;

  private debug = import.meta.env.DEV;

  constructor(url?: string) {
    this.url = url ?? deriveWsUrl();
  }

  /**
   * Connect to the server. Resolves when the connection opens; rejects if it closes first.
   * After an unexpected close the client reconnects automatically (up to 5 attempts).
   */
  connect(): Promise<void> {
    if (this.state === "open") return Promise.resolve();
    if (this.pendingConnect) return this.pendingConnect;
    // Connecting now supersedes a scheduled reconnection attempt
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.closedIntentionally = false;
    // An attempt made while reconnecting is still part of reconnecting
    this.setState(this.state === "reconnecting" ? "reconnecting" : "connecting");

    const connecting = new Promise<void>((resolve, reject) => {
      let opened = false;
      let ws: WebSocket;
      try {
        if (this.debug) console.log(`[WebSocketClient] Connecting to ${this.url}`);
        ws = new WebSocket(this.url);
      } catch (error) {
        this.pendingConnect = null;
        this.setState("closed");
        reject(error);
        return;
      }
      this.ws = ws;

      ws.onopen = () => {
        // A socket this client has since let go of (disconnect() closed it while it was opening)
        if (this.ws !== ws) return;
        opened = true;
        const reconnected = this.wasConnected;
        this.pendingConnect = null;
        this.wasConnected = true;
        this.reconnectAttempts = 0;
        this.startPingInterval();
        // Listeners run before queued messages are flushed, so a reconnecting session can
        // resume itself before any command queued while it was offline reaches the server.
        this.setState("open", reconnected);
        const queued = this.messageQueue.splice(0);
        queued.forEach((m) => this.send(m));
        resolve();
      };

      ws.onmessage = (event) => {
        if (this.ws === ws) this.handleMessage(event.data);
      };

      ws.onerror = (error) => {
        console.error("[WebSocketClient] Error:", error);
      };

      ws.onclose = () => {
        if (!opened) reject(new Error("WebSocket closed before it opened"));
        // Only the current socket's close says anything about the connection
        if (this.ws !== ws) return;
        this.ws = null;
        this.pendingConnect = null;
        this.stopPingInterval();

        if (!this.closedIntentionally && this.wasConnected && this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          if (this.debug) console.log(`[WebSocketClient] Reconnecting (attempt ${this.reconnectAttempts})`);
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connect().catch(() => {
              /* onclose schedules the next attempt */
            });
          }, this.reconnectDelay * this.reconnectAttempts);
          this.setState("reconnecting");
        } else {
          // Given up: what was queued for the connection is not sent on a later one
          this.messageQueue = [];
          this.setState("closed");
        }
      };
    });
    this.pendingConnect = connecting;
    return connecting;
  }

  /** Close the connection for good: no reconnection, queued messages are dropped. */
  disconnect(): void {
    this.closedIntentionally = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.stopPingInterval();
    this.messageQueue = [];
    this.pendingConnect = null;
    const ws = this.ws;
    this.ws = null;
    this.setState("closed");
    ws?.close();
  }

  /** Send a message; queued until the connection (re)opens if not connected. */
  send(message: ClientMessage): void {
    if (this.state !== "open" || !this.ws) {
      if (this.debug) console.log("[WS-QUEUE] Message queued:", message.type);
      this.messageQueue.push(message);
      return;
    }
    if (this.debug && message.type !== "ping") console.log(`[WS-OUT] ${message.type}`);
    this.ws.send(JSON.stringify(message));
  }

  /**
   * Whether a message of one of these types is queued, to be sent when the connection opens. A connection
   * listener can ask this before the queue is flushed, to tell a message still to go from one sent on the
   * connection that dropped.
   */
  hasQueued(...types: ClientMessage["type"][]): boolean {
    return this.messageQueue.some((m) => types.includes(m.type));
  }

  /** Register a handler for a server message type. */
  on<K extends ServerMessage["type"]>(type: K, handler: (message: ServerMessageOf<K>) => void): void {
    if (!this.messageHandlers.has(type)) this.messageHandlers.set(type, new Set());
    // Messages are routed by their type, so this handler only ever receives a ServerMessageOf<K>
    this.messageHandlers.get(type)?.add(handler as (message: ServerMessage) => void);
  }

  /** Register a listener for changes of the connection state. */
  onConnectionChange(listener: ConnectionListener): void {
    this.connectionListeners.add(listener);
  }

  private setState(state: ConnectionState, reconnected = false): void {
    if (state === this.state) return;
    this.state = state;
    this.connectionListeners.forEach((l) => l(state, reconnected));
  }

  private handleMessage(data: string): void {
    let message: ServerMessage;
    try {
      message = JSON.parse(data) as ServerMessage;
    } catch (error) {
      console.error("[WS-ERROR] Failed to parse message:", error);
      return;
    }
    if (message.type === "error") console.error("[WS-ERROR]", message.data);
    else if (this.debug && message.type !== "pong" && message.type !== "textChunk")
      console.log(`[WS-IN] ${message.type}`);

    this.messageHandlers.get(message.type)?.forEach((handler) => {
      try {
        handler(message);
      } catch (error) {
        console.error(`[WS-ERROR] Handler for ${message.type} failed:`, error);
      }
    });
  }

  private startPingInterval(): void {
    this.stopPingInterval();
    this.pingInterval = setInterval(() => {
      if (this.state === "open") this.send({ type: "ping", timestamp: Date.now() });
    }, 30000);
  }

  private stopPingInterval(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }

  // ============= Convenience methods =============

  newGame(request: Omit<NewGameRequest, "type">): void {
    // A library adventure is played from the server's copy of its outline
    const { adventureOutline, ...rest } = request;
    if (request.adventureId) this.send({ type: "newGame", ...rest });
    else this.send({ type: "newGame", ...rest, adventureOutline });
  }

  loadGame(gameId: string, features?: FeatureRequest): void {
    this.send({ type: "loadGame", gameId, ...features });
  }

  resumeSession(sessionId: string): void {
    this.send({ type: "resumeSession", sessionId });
  }

  sendStreamCommand(command: string, imageGeneration?: boolean): void {
    this.send({ type: "streamCommand", command, imageGeneration });
  }

  sendAudio(audioBase64: string, imageGeneration?: boolean, mimeType?: string): void {
    this.send({ type: "audioCommand", audio: audioBase64, imageGeneration, mimeType });
  }
}
