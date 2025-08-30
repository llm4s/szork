/**
 * WebSocket client for game communication
 */

import type { 
  ClientMessage, 
  ServerMessage,
  NewGameRequest,
  ConnectedMessage,
  GameStartedMessage,
  GameLoadedMessage,
  CommandResponseMessage,
  TextChunkMessage,
  StreamCompleteMessage,
  TranscriptionMessage,
  ImageReadyMessage,
  MusicReadyMessage,
  GamesListMessage,
  ErrorMessage
} from '@/types/WebSocketProtocol';

export type MessageHandler<T extends ServerMessage> = (message: T) => void;

export class WebSocketClient {
  private ws: WebSocket | null = null;
  private url: string;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private messageHandlers = new Map<string, Set<MessageHandler<any>>>();
  private isConnecting = false;
  private isConnected = false;
  private messageQueue: ClientMessage[] = [];
  private pingInterval: NodeJS.Timeout | null = null;
  private serverInstanceId: string | null = null;
  private serverAvailable = false;
  
  constructor(url: string = 'ws://localhost:9002') {
    this.url = url;
  }
  
  /**
   * Connect to the WebSocket server
   */
  async connect(): Promise<void> {
    if (this.isConnected || this.isConnecting) {
      console.log('[WebSocketClient] Already connected or connecting');
      return;
    }
    
    this.isConnecting = true;
    
    return new Promise((resolve, reject) => {
      try {
        console.log(`[WebSocketClient] Connecting to ${this.url}`);
        this.ws = new WebSocket(this.url);
        
        this.ws.onopen = () => {
          console.log('[WebSocketClient] Connected');
          this.isConnecting = false;
          this.isConnected = true;
          this.reconnectAttempts = 0;
          
          // Send any queued messages
          while (this.messageQueue.length > 0) {
            const message = this.messageQueue.shift();
            if (message) {
              this.send(message);
            }
          }
          
          // Start ping interval to keep connection alive
          this.startPingInterval();
          
          resolve();
        };
        
        this.ws.onmessage = (event) => {
          this.handleMessage(event.data);
        };
        
        this.ws.onerror = (error) => {
          console.error('[WebSocketClient] Error:', error);
          this.isConnecting = false;
          this.isConnected = false;
        };
        
        this.ws.onclose = () => {
          console.log('[WebSocketClient] Connection closed');
          this.isConnecting = false;
          this.isConnected = false;
          this.stopPingInterval();
          
          // Attempt to reconnect
          if (this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++;
            console.log(`[WebSocketClient] Reconnecting... (attempt ${this.reconnectAttempts})`);
            setTimeout(() => {
              this.connect();
            }, this.reconnectDelay * this.reconnectAttempts);
          }
        };
        
      } catch (error) {
        console.error('[WebSocketClient] Failed to connect:', error);
        this.isConnecting = false;
        reject(error);
      }
    });
  }
  
  /**
   * Disconnect from the server
   */
  disconnect(): void {
    if (this.ws) {
      this.stopPingInterval();
      this.ws.close();
      this.ws = null;
      this.isConnected = false;
    }
  }
  
  /**
   * Send a message to the server
   */
  send(message: ClientMessage): void {
    if (!this.isConnected || !this.ws) {
      console.log('[WS-QUEUE] Message queued:', message.type);
      this.messageQueue.push(message);
      return;
    }
    
    // Log outgoing message with key details
    let logMessage = `[WS-OUT] Type: ${message.type}`;
    switch (message.type) {
      case 'newGame':
        logMessage += ` | Theme: ${message.theme || 'default'} | ArtStyle: ${message.artStyle || 'default'} | ImageGen: ${message.imageGeneration}`;
        break;
      case 'loadGame':
        logMessage += ` | GameId: ${message.gameId}`;
        break;
      case 'command':
        logMessage += ` | Command: '${message.command.substring(0, 50)}${message.command.length > 50 ? '...' : ''}'`;
        break;
      case 'streamCommand':
        logMessage += ` | Command: '${message.command.substring(0, 50)}${message.command.length > 50 ? '...' : ''}' | ImageGen: ${message.imageGeneration}`;
        break;
      case 'audioCommand':
        logMessage += ` | AudioLength: ${message.audio.length} bytes`;
        break;
      case 'getImage':
        logMessage += ` | MessageIndex: ${message.messageIndex}`;
        break;
      case 'getMusic':
        logMessage += ` | MessageIndex: ${message.messageIndex}`;
        break;
      case 'ping':
        logMessage = `[WS-DEBUG] Type: ping | Timestamp: ${message.timestamp}`;
        break;
    }
    
    // Use different log level for ping messages
    if (message.type === 'ping') {
      console.debug(logMessage);
    } else {
      console.log(logMessage);
    }
    
    const json = JSON.stringify(message);
    this.ws.send(json);
  }
  
  /**
   * Register a handler for a specific message type
   */
  on<T extends ServerMessage>(type: T['type'], handler: MessageHandler<T>): void {
    if (!this.messageHandlers.has(type)) {
      this.messageHandlers.set(type, new Set());
    }
    this.messageHandlers.get(type)?.add(handler);
  }
  
  /**
   * Remove a handler for a specific message type
   */
  off<T extends ServerMessage>(type: T['type'], handler: MessageHandler<T>): void {
    this.messageHandlers.get(type)?.delete(handler);
  }
  
  /**
   * Handle incoming messages from the server
   */
  private handleMessage(data: string): void {
    try {
      const message = JSON.parse(data) as ServerMessage;
      
      // Log incoming message with key details
      let logMessage = `[WS-IN] Type: ${message.type}`;
      const msgData = (message as any).data;
      
      switch (message.type) {
        case 'connected':
          logMessage += ` | Version: ${msgData?.version} | ServerInstance: ${msgData?.serverInstanceId}`;
          // Check for server instance change
          this.handleServerInstanceChange(msgData?.serverInstanceId);
          this.serverAvailable = true;
          break;
        case 'gameStarted':
          logMessage += ` | GameId: ${msgData?.gameId} | SessionId: ${msgData?.sessionId} | MsgIdx: ${msgData?.messageIndex} | TextLen: ${msgData?.text?.length} | HasImage: ${msgData?.hasImage}`;
          break;
        case 'gameLoaded':
          logMessage += ` | GameId: ${msgData?.gameId} | SessionId: ${msgData?.sessionId} | Messages: ${msgData?.conversation?.length}`;
          break;
        case 'commandResponse':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | TextLen: ${msgData?.text?.length} | HasImage: ${msgData?.hasImage}`;
          break;
        case 'textChunk':
          logMessage += ` | ChunkNum: ${msgData?.chunkNumber} | TextLen: ${msgData?.text?.length}`;
          break;
        case 'streamComplete':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | Chunks: ${msgData?.totalChunks} | Duration: ${msgData?.duration}ms | HasImage: ${msgData?.hasImage}`;
          break;
        case 'transcription':
          logMessage += ` | TextLen: ${msgData?.text?.length}`;
          break;
        case 'imageReady':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | ImageLen: ${msgData?.image?.length} bytes`;
          break;
        case 'musicReady':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | Mood: ${msgData?.mood} | MusicLen: ${msgData?.music?.length} bytes`;
          break;
        case 'imageData':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | Status: ${msgData?.status}`;
          break;
        case 'musicData':
          logMessage += ` | MsgIdx: ${msgData?.messageIndex} | Status: ${msgData?.status}`;
          break;
        case 'gamesList':
          logMessage += ` | Games: ${msgData?.games?.length}`;
          break;
        case 'error':
          logMessage = `[WS-ERROR] Type: error | Error: ${msgData?.error}`;
          break;
        case 'pong':
          logMessage = `[WS-DEBUG] Type: pong | Timestamp: ${msgData?.timestamp}`;
          break;
      }
      
      // Use different log levels based on message type
      if (message.type === 'pong') {
        console.debug(logMessage);
      } else if (message.type === 'error') {
        console.error(logMessage);
      } else {
        console.log(logMessage);
      }
      
      // Call registered handlers for this message type
      const handlers = this.messageHandlers.get(message.type);
      if (handlers) {
        handlers.forEach(handler => handler(message));
      }
      
      // Also call generic handlers (registered with '*')
      const genericHandlers = this.messageHandlers.get('*');
      if (genericHandlers) {
        genericHandlers.forEach(handler => handler(message));
      }
      
    } catch (error) {
      console.error('[WS-ERROR] Failed to parse message:', error);
    }
  }
  
  /**
   * Start sending periodic ping messages to keep connection alive
   */
  private startPingInterval(): void {
    this.pingInterval = setInterval(() => {
      if (this.isConnected) {
        this.send({ type: 'ping', timestamp: Date.now() });
      }
    }, 30000); // Ping every 30 seconds
  }
  
  /**
   * Stop sending ping messages
   */
  private stopPingInterval(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }
  
  /**
   * Handle server instance change
   */
  private handleServerInstanceChange(newInstanceId: string): void {
    if (this.serverInstanceId && this.serverInstanceId !== newInstanceId) {
      console.warn('[WebSocketClient] Server instance changed! Clearing game state...');
      // Clear local storage and session storage
      localStorage.removeItem('soundEnabled');
      localStorage.removeItem('musicEnabled');
      localStorage.removeItem('currentGameId');
      sessionStorage.clear();
      
      // Emit an event for the app to handle
      window.dispatchEvent(new CustomEvent('serverInstanceChanged', { 
        detail: { 
          oldInstanceId: this.serverInstanceId, 
          newInstanceId 
        } 
      }));
    }
    this.serverInstanceId = newInstanceId;
    console.log('[WebSocketClient] Server instance ID:', this.serverInstanceId);
  }
  
  /**
   * Check if connected
   */
  get connected(): boolean {
    return this.isConnected;
  }
  
  /**
   * Check if server is available
   */
  get isServerAvailable(): boolean {
    return this.serverAvailable;
  }
  
  /**
   * Get the current server instance ID
   */
  getServerInstanceId(): string | null {
    return this.serverInstanceId;
  }
  
  /**
   * Check server availability with timeout
   */
  async checkServerAvailability(timeout = 3000): Promise<boolean> {
    return new Promise((resolve) => {
      const timeoutId = setTimeout(() => {
        this.serverAvailable = false;
        resolve(false);
      }, timeout);
      
      // Try to connect
      this.connect().then(() => {
        clearTimeout(timeoutId);
        this.serverAvailable = this.isConnected;
        resolve(this.isConnected);
      }).catch(() => {
        clearTimeout(timeoutId);
        this.serverAvailable = false;
        resolve(false);
      });
    });
  }
  
  // ============= Convenience methods for common operations =============
  
  /**
   * Start a new game
   */
  newGame(theme?: string, artStyle?: string, imageGeneration = true, adventureOutline?: any): void {
    this.send({
      type: 'newGame',
      theme,
      artStyle,
      imageGeneration
      // adventureOutline is not part of the protocol yet, will be added later
    } as NewGameRequest);
  }
  
  /**
   * Load an existing game
   */
  loadGame(gameId: string): void {
    this.send({
      type: 'loadGame',
      gameId
    });
  }
  
  /**
   * Send a regular command
   */
  sendCommand(command: string): void {
    this.send({
      type: 'command',
      command
    });
  }
  
  /**
   * Send a streaming command
   */
  sendStreamCommand(command: string, imageGeneration?: boolean): void {
    this.send({
      type: 'streamCommand',
      command,
      imageGeneration
    });
  }
  
  /**
   * Send audio for transcription and processing
   */
  sendAudio(audioBase64: string): void {
    this.send({
      type: 'audioCommand',
      audio: audioBase64
    });
  }
  
  /**
   * Request list of saved games
   */
  listGames(): void {
    this.send({
      type: 'listGames'
    });
  }
  
  /**
   * Request image for a specific message
   */
  getImage(messageIndex: number): void {
    this.send({
      type: 'getImage',
      messageIndex
    });
  }
  
  /**
   * Request music for a specific message
   */
  getMusic(messageIndex: number): void {
    this.send({
      type: 'getMusic',
      messageIndex
    });
  }
}