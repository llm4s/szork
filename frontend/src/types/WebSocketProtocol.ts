/**
 * TypeScript types matching the Scala WebSocket protocol
 */

// ============= Client Messages (sent to server) =============

export interface NewGameRequest {
  type: 'newGame';
  theme?: string;
  artStyle?: string;
  imageGeneration?: boolean;
}

export interface LoadGameRequest {
  type: 'loadGame';
  gameId: string;
}

export interface CommandRequest {
  type: 'command';
  command: string;
}

export interface StreamCommandRequest {
  type: 'streamCommand';
  command: string;
  imageGeneration?: boolean;
}

export interface AudioCommandRequest {
  type: 'audioCommand';
  audio: string; // Base64 encoded audio
}

export interface GetImageRequest {
  type: 'getImage';
  messageIndex: number;
}

export interface GetMusicRequest {
  type: 'getMusic';
  messageIndex: number;
}

export interface ListGamesRequest {
  type: 'listGames';
}

export interface PingMessage {
  type: 'ping';
  timestamp?: number;
}

export type ClientMessage = 
  | NewGameRequest 
  | LoadGameRequest 
  | CommandRequest 
  | StreamCommandRequest 
  | AudioCommandRequest 
  | GetImageRequest 
  | GetMusicRequest 
  | ListGamesRequest 
  | PingMessage;

// ============= Server Messages (received from server) =============

export interface ConnectedMessage {
  type: 'connected';
  data: {
    message: string;
    version: string;
    serverInstanceId: string;
  };
}

export interface GameStartedMessage {
  type: 'gameStarted';
  data: {
    sessionId: string;
    gameId: string;
    text: string;
    messageIndex: number;
    scene?: SceneData;
    audio?: string;
    hasImage?: boolean;
    hasMusic?: boolean;
  };
}

export interface GameLoadedMessage {
  type: 'gameLoaded';
  data: {
    sessionId: string;
    gameId: string;
    conversation: ConversationEntry[];
    currentLocation?: string;
    currentScene?: SceneData;
  };
}

export interface CommandResponseMessage {
  type: 'commandResponse';
  data: {
    text: string;
    messageIndex: number;
    command: string;
    scene?: SceneData;
    audio?: string;
    hasImage?: boolean;
    hasMusic?: boolean;
  };
}

export interface TextChunkMessage {
  type: 'textChunk';
  data: {
    text: string;
    chunkNumber: number;
  };
}

export interface StreamCompleteMessage {
  type: 'streamComplete';
  data: {
    messageIndex: number;
    totalChunks: number;
    duration: number;
    scene?: SceneData;
    audio?: string;
    hasImage?: boolean;
    hasMusic?: boolean;
  };
}

export interface TranscriptionMessage {
  type: 'transcription';
  data: {
    text: string;
  };
}

export interface ImageReadyMessage {
  type: 'imageReady';
  data: {
    messageIndex: number;
    image: string; // Base64 encoded image
  };
}

export interface MusicReadyMessage {
  type: 'musicReady';
  data: {
    messageIndex: number;
    music: string; // Base64 encoded audio
    mood: string;
  };
}

export interface ImageDataMessage {
  type: 'imageData';
  data: {
    messageIndex: number;
    image: string;
    status: string;
  };
}

export interface MusicDataMessage {
  type: 'musicData';
  data: {
    messageIndex: number;
    music: string;
    mood: string;
    status: string;
  };
}

export interface GamesListMessage {
  type: 'gamesList';
  data: {
    games: GameInfo[];
  };
}

export interface ErrorMessage {
  type: 'error';
  data: {
    error: string;
    details?: string;
  };
}

export interface PongMessage {
  type: 'pong';
  data: {
    timestamp: number;
  };
}

export type ServerMessage = 
  | ConnectedMessage 
  | GameStartedMessage 
  | GameLoadedMessage 
  | CommandResponseMessage 
  | TextChunkMessage 
  | StreamCompleteMessage 
  | TranscriptionMessage 
  | ImageReadyMessage 
  | MusicReadyMessage 
  | ImageDataMessage 
  | MusicDataMessage 
  | GamesListMessage 
  | ErrorMessage 
  | PongMessage;

// ============= Data Types =============

export interface SceneData {
  locationName: string;
  exits: ExitData[];
  items?: string[];
  npcs?: string[];
}

export interface ExitData {
  direction: string;
  description: string;
}

export interface ConversationEntry {
  role: string;
  content: string;
}

export interface GameInfo {
  gameId: string;
  theme: string;
  timestamp: number;
  locationName: string;
}