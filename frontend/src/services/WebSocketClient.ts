/**
 * WebSocket client for real-time game communication.
 *
 * Manages the connection lifecycle (including automatic reconnection after an unexpected close),
 * queues messages sent while disconnected, and routes server messages to typed handlers.
 */

import type { ClientMessage, ServerMessage, FeatureRequest } from "@/types/WebSocketProtocol";
import type { AdventureOutline } from "@/types/api";

/** Callback handler for server messages of a specific type. */
export type MessageHandler<T extends ServerMessage> = (message: T) => void;

/** Called when the connection opens (`reconnected` is true after an unexpected close) or closes. */
export type ConnectionListener = (connected: boolean, reconnected: boolean) => void;

/**
 * Derives the WebSocket URL based on environment and location.
 * Checks VITE_WS_URL env var, then dev proxy, then defaults to port 9002.
 */
function deriveWsUrl(): string {
  const envWs = import.meta.env.VITE_WS_URL;
  if (envWs && envWs.trim().length > 0) return envWs.trim();

  try {
    const { protocol, hostname, port } = window.location;
    const wsScheme = protocol === "https:" ? "wss:" : "ws:";
    // Vite dev server proxies '/ws' to the backend WS server
    if (port === "3090") return `${wsScheme}//${hostname}:${port}/ws`;
    return `${wsScheme}//${hostname}:9002`;
  } catch {
    return "ws://localhost:9002";
  }
}

export class WebSocketClient {
  private ws: WebSocket | null = null;
  private url: string;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private messageHandlers = new Map<string, Set<MessageHandler<ServerMessage>>>();
  private connectionListeners = new Set<ConnectionListener>();
  private isConnecting = false;
  private isConnected = false;
  // Set by disconnect(): a deliberate close must not trigger reconnection
  private closedIntentionally = false;
  private wasConnected = false;
  private messageQueue: ClientMessage[] = [];
  private pingInterval: ReturnType<typeof setInterval> | null = null;

  private debug = import.meta.env.MODE !== "production";

  constructor(url?: string) {
    this.url = url ?? deriveWsUrl();
  }

  /**
   * Connect to the server. Resolves when the connection opens; rejects if it closes first.
   * After an unexpected close the client reconnects automatically (up to 5 attempts).
   */
  connect(): Promise<void> {
    if (this.isConnected || this.isConnecting) return Promise.resolve();
    // Connecting now supersedes a scheduled reconnection attempt
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.closedIntentionally = false;
    this.isConnecting = true;

    return new Promise((resolve, reject) => {
      let opened = false;
      let ws: WebSocket;
      try {
        if (this.debug) console.log(`[WebSocketClient] Connecting to ${this.url}`);
        ws = new WebSocket(this.url);
      } catch (error) {
        this.isConnecting = false;
        reject(error);
        return;
      }
      this.ws = ws;

      ws.onopen = () => {
        opened = true;
        const reconnected = this.wasConnected;
        this.isConnecting = false;
        this.isConnected = true;
        this.wasConnected = true;
        this.reconnectAttempts = 0;
        this.startPingInterval();
        // Listeners run before queued messages are flushed, so a reconnecting session can
        // resume itself before any command queued while it was offline reaches the server.
        this.connectionListeners.forEach((l) => l(true, reconnected));
        const queued = this.messageQueue.splice(0);
        queued.forEach((m) => this.send(m));
        resolve();
      };

      ws.onmessage = (event) => this.handleMessage(event.data);

      ws.onerror = (error) => {
        console.error("[WebSocketClient] Error:", error);
      };

      ws.onclose = () => {
        if (this.ws === ws) this.ws = null;
        const wasOpen = this.isConnected;
        this.isConnecting = false;
        this.isConnected = false;
        this.stopPingInterval();
        if (wasOpen) this.connectionListeners.forEach((l) => l(false, false));
        if (!opened) reject(new Error("WebSocket closed before it opened"));

        if (!this.closedIntentionally && this.wasConnected && this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          if (this.debug) console.log(`[WebSocketClient] Reconnecting (attempt ${this.reconnectAttempts})`);
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connect().catch(() => {
              /* onclose schedules the next attempt */
            });
          }, this.reconnectDelay * this.reconnectAttempts);
        }
      };
    });
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
    const ws = this.ws;
    this.ws = null;
    this.isConnected = false;
    this.isConnecting = false;
    ws?.close();
  }

  /** Send a message; queued until the connection (re)opens if not connected. */
  send(message: ClientMessage): void {
    if (!this.isConnected || !this.ws) {
      if (this.debug) console.log("[WS-QUEUE] Message queued:", message.type);
      this.messageQueue.push(message);
      return;
    }
    if (this.debug && message.type !== "ping") console.log(`[WS-OUT] ${message.type}`);
    this.ws.send(JSON.stringify(message));
  }

  /** Register a handler for a server message type. */
  on<T extends ServerMessage>(type: T["type"], handler: MessageHandler<T>): void {
    if (!this.messageHandlers.has(type)) this.messageHandlers.set(type, new Set());
    this.messageHandlers.get(type)?.add(handler as MessageHandler<ServerMessage>);
  }

  off<T extends ServerMessage>(type: T["type"], handler: MessageHandler<T>): void {
    this.messageHandlers.get(type)?.delete(handler as MessageHandler<ServerMessage>);
  }

  /** Register a listener for the connection opening or closing. */
  onConnectionChange(listener: ConnectionListener): void {
    this.connectionListeners.add(listener);
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
      if (this.isConnected) this.send({ type: "ping", timestamp: Date.now() });
    }, 30000);
  }

  private stopPingInterval(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }

  get connected(): boolean {
    return this.isConnected;
  }

  // ============= Convenience methods =============

  newGame(
    theme?: string,
    artStyle?: string,
    adventureOutline?: AdventureOutline | null,
    features?: FeatureRequest
  ): void {
    this.send({ type: "newGame", theme, artStyle, adventureOutline, ...features });
  }

  loadGame(gameId: string, features?: FeatureRequest): void {
    this.send({ type: "loadGame", gameId, ...features });
  }

  resumeSession(sessionId: string): void {
    this.send({ type: "resumeSession", sessionId });
  }

  sendCommand(command: string): void {
    this.send({ type: "command", command });
  }

  sendStreamCommand(command: string, imageGeneration?: boolean): void {
    this.send({ type: "streamCommand", command, imageGeneration });
  }

  sendAudio(audioBase64: string, imageGeneration?: boolean): void {
    this.send({ type: "audioCommand", audio: audioBase64, imageGeneration });
  }
}
