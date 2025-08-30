/**
 * Composable for WebSocket-based game communication
 */

import { ref, Ref, onUnmounted, nextTick } from 'vue';
import { WebSocketClient } from '@/services/WebSocketClient';
import type { 
  SceneData,
  ConversationEntry,
  GameInfo
} from '@/types/WebSocketProtocol';

export interface GameMessage {
  text: string;
  isUser: boolean;
  timestamp: Date;
  image?: string;  // Base64 image data (Vue component expects 'image', not 'imageUrl')
  imageLoading?: boolean;
  hasImage?: boolean;
  backgroundMusic?: string;
  musicMood?: string;
  scene?: SceneData;
  messageIndex?: number;
  isStreaming?: boolean;
}

export function useWebSocketGame() {
  const wsClient = ref<WebSocketClient | null>(null);
  const sessionId = ref<string | null>(null);
  const gameId = ref<string | null>(null);
  const messages = ref<GameMessage[]>([]);
  const isConnected = ref(false);
  const isStreaming = ref(false);
  const savedGames = ref<GameInfo[]>([]);
  const currentStreamingMessage = ref<GameMessage | null>(null);
  
  // Callback for auto-scrolling during streaming
  let scrollCallback: (() => void) | null = null;
  
  // Callbacks for audio playback
  let playBackgroundMusicCallback: ((musicBase64: string, mood: string) => void) | null = null;
  let playAudioNarrationCallback: ((audioBase64: string) => void) | null = null;
  
  // Helper function for logging game-specific events
  const log = (message: string, ...args: any[]) => {
    console.log(`[Game] ${message}`, ...args);
  };
  
  /**
   * Connect to WebSocket server
   */
  const connect = async (): Promise<void> => {
    if (wsClient.value?.connected) {
      log('Already connected');
      return;
    }
    
    log('Connecting to WebSocket server...');
    wsClient.value = new WebSocketClient();
    
    // Register message handlers
    setupMessageHandlers();
    
    try {
      await wsClient.value.connect();
      isConnected.value = true;
      log('Connected successfully');
    } catch (error) {
      log('Failed to connect:', error);
      throw error;
    }
  };
  
  /**
   * Setup message handlers for all server events
   */
  const setupMessageHandlers = () => {
    if (!wsClient.value) return;
    
    // Connected
    wsClient.value.on('connected', (msg) => {
      const data = (msg as any).data;
      // Connection confirmation handled by WebSocketClient
    });
    
    // Game started
    wsClient.value.on('gameStarted', (msg) => {
      const data = (msg as any).data;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      // Add initial message
      const gameMessage: GameMessage = {
        text: data.text,
        isUser: false,
        timestamp: new Date(),
        scene: data.scene,
        messageIndex: data.messageIndex,
        hasImage: data.hasImage
      };
      
      // Set imageLoading flag if image is being generated
      if (data.hasImage) {
        gameMessage.imageLoading = true;
      }
      
      messages.value.push(gameMessage);
      
      // Handle audio if present
      if (data.audio) {
        playAudioNarration(data.audio);
      }
      
      // Scroll to show the initial game message
      if (scrollCallback) {
        nextTick(() => {
          scrollCallback!();
        });
      }
    });
    
    // Game loaded
    wsClient.value.on('gameLoaded', (msg) => {
      const data = (msg as any).data;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      
      // Restore conversation history
      messages.value = data.conversation.map((entry: ConversationEntry, index: number) => ({
        text: entry.content,
        isUser: entry.role === 'user',
        timestamp: new Date(),
        messageIndex: index
      }));
      
      // Set current scene if available
      if (data.currentScene) {
        const lastMessage = messages.value[messages.value.length - 1];
        if (lastMessage && !lastMessage.isUser) {
          lastMessage.scene = data.currentScene;
        }
      }
      
      // Trigger auto-scroll to bottom after loading conversation history
      if (scrollCallback) {
        // Use nextTick to ensure DOM is updated with all messages before scrolling
        nextTick(() => {
          scrollCallback!();
        });
      }
    });
    
    // Command response
    wsClient.value.on('commandResponse', (msg) => {
      const data = (msg as any).data;
      const gameMessage: GameMessage = {
        text: data.text,
        isUser: false,
        timestamp: new Date(),
        scene: data.scene,
        messageIndex: data.messageIndex,
        hasImage: data.hasImage
      };
      
      // Set imageLoading flag if image is being generated
      if (data.hasImage) {
        gameMessage.imageLoading = true;
      }
      
      messages.value.push(gameMessage);
      
      if (data.audio) {
        playAudioNarration(data.audio);
      }
    });
    
    // Text chunk (for streaming)
    wsClient.value.on('textChunk', (msg) => {
      const data = (msg as any).data;
      if (!currentStreamingMessage.value) {
        // Should not happen, but handle gracefully
        log('Warning: Received text chunk without active streaming message');
        return;
      }
      
      // Append text to streaming message
      currentStreamingMessage.value.text += data.text;
      
      // Trigger auto-scroll if callback is set
      if (scrollCallback) {
        // Use nextTick to ensure DOM is updated before scrolling
        nextTick(() => {
          scrollCallback!();
        });
      }
    });
    
    // Stream complete
    wsClient.value.on('streamComplete', (msg) => {
      const data = (msg as any).data;
      isStreaming.value = false;
      
      if (currentStreamingMessage.value) {
        currentStreamingMessage.value.isStreaming = false;
        currentStreamingMessage.value.scene = data.scene;
        currentStreamingMessage.value.messageIndex = data.messageIndex;
        currentStreamingMessage.value.hasImage = data.hasImage;
        
        // Set imageLoading flag if image is being generated
        if (data.hasImage) {
          currentStreamingMessage.value.imageLoading = true;
        }
        
        currentStreamingMessage.value = null;
      }
      
      if (data.audio) {
        playAudioNarration(data.audio);
      }
    });
    
    // Transcription
    wsClient.value.on('transcription', (msg) => {
      const data = (msg as any).data;
      // Add user message with transcribed text
      messages.value.push({
        text: data.text,
        isUser: true,
        timestamp: new Date()
      });
    });
    
    // Image ready
    wsClient.value.on('imageReady', (msg) => {
      const data = (msg as any).data;
      const message = messages.value.find(m => m.messageIndex === data.messageIndex);
      if (message) {
        // Vue component expects 'image' property, not 'imageUrl'
        (message as any).image = data.image;
        message.imageLoading = false;
      } else {
        log(`Warning: Could not find message with index ${data.messageIndex} to attach image`);
      }
    });
    
    // Music ready
    wsClient.value.on('musicReady', (msg) => {
      const data = (msg as any).data;
      const message = messages.value.find(m => m.messageIndex === data.messageIndex);
      if (message) {
        message.backgroundMusic = data.music;
        message.musicMood = data.mood;
        if (playBackgroundMusicCallback) {
          playBackgroundMusicCallback(data.music, data.mood);
        }
      }
    });
    
    // Games list
    wsClient.value.on('gamesList', (msg) => {
      const data = (msg as any).data;
      savedGames.value = data.games;
    });
    
    // Error
    wsClient.value.on('error', (msg) => {
      const data = (msg as any).data;
      // Add error message to chat
      messages.value.push({
        text: `Error: ${data.error}`,
        isUser: false,
        timestamp: new Date()
      });
    });
    
    // Pong
    wsClient.value.on('pong', (msg) => {
      // Pong handling and latency measurement handled by WebSocketClient
    });
  };
  
  /**
   * Start a new game
   */
  const startNewGame = async (theme?: string, artStyle?: string, imageGeneration = true, adventureOutline?: any) => {
    if (!wsClient.value?.connected) {
      await connect();
    }
    
    wsClient.value?.newGame(theme, artStyle, imageGeneration, adventureOutline);
  };
  
  /**
   * Load an existing game
   */
  const loadGame = async (gameIdToLoad: string) => {
    if (!wsClient.value?.connected) {
      await connect();
    }
    
    wsClient.value?.loadGame(gameIdToLoad);
  };
  
  /**
   * Send a command (regular or streaming)
   */
  const sendCommand = async (command: string, streaming = true) => {
    if (!wsClient.value?.connected) {
      await connect();
    }
    
    // Add user message
    messages.value.push({
      text: command,
      isUser: true,
      timestamp: new Date()
    });
    
    if (streaming) {
      // Create streaming message placeholder
      currentStreamingMessage.value = {
        text: '',
        isUser: false,
        timestamp: new Date(),
        isStreaming: true
      };
      messages.value.push(currentStreamingMessage.value);
      isStreaming.value = true;
      
      wsClient.value?.sendStreamCommand(command);
    } else {
      wsClient.value?.sendCommand(command);
    }
  };
  
  /**
   * Send audio command
   */
  const sendAudioCommand = async (audioBase64: string) => {
    if (!wsClient.value?.connected) {
      await connect();
    }
    
    wsClient.value?.sendAudio(audioBase64);
  };
  
  /**
   * Get list of saved games
   */
  const getSavedGames = async () => {
    if (!wsClient.value?.connected) {
      await connect();
    }
    
    wsClient.value?.listGames();
  };
  
  /**
   * Disconnect from server
   */
  const disconnect = () => {
    wsClient.value?.disconnect();
    wsClient.value = null;
    isConnected.value = false;
  };
  
  // Audio playback functions (uses callbacks if provided)
  const playAudioNarration = (audioBase64: string) => {
    if (playAudioNarrationCallback) {
      playAudioNarrationCallback(audioBase64);
    }
  };
  
  const playBackgroundMusic = (musicBase64: string, mood: string) => {
    if (playBackgroundMusicCallback) {
      playBackgroundMusicCallback(musicBase64, mood);
    }
  };
  
  // Cleanup on unmount
  onUnmounted(() => {
    disconnect();
  });
  
  /**
   * Set callback for auto-scrolling during streaming
   */
  const setScrollCallback = (callback: () => void) => {
    scrollCallback = callback;
  };
  
  /**
   * Set callback for background music playback
   */
  const setPlayBackgroundMusicCallback = (callback: (musicBase64: string, mood: string) => void) => {
    playBackgroundMusicCallback = callback;
  };
  
  /**
   * Set callback for audio narration playback
   */
  const setPlayAudioNarrationCallback = (callback: (audioBase64: string) => void) => {
    playAudioNarrationCallback = callback;
  };
  
  return {
    // State
    sessionId,
    gameId,
    messages,
    isConnected,
    isStreaming,
    savedGames,
    
    // Methods
    connect,
    disconnect,
    startNewGame,
    loadGame,
    sendCommand,
    sendAudioCommand,
    getSavedGames,
    setScrollCallback,
    setPlayBackgroundMusicCallback,
    setPlayAudioNarrationCallback,
    
    // Expose log function
    log
  };
}