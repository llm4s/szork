/**
 * TypeScript types matching the Scala WebSocket protocol
 * (src/main/scala/org/llm4s/szork/websocket/protocol/WebSocketProtocol.scala).
 *
 * Client messages are sent as `{ type, ...fields }`; server messages arrive as `{ type, data: {...} }`.
 * Server fields marked optional are omitted when absent; all others are always sent.
 */

import type { AdventureOutline } from "./api";

// ============= Client Messages (sent to server) =============

export interface FeatureRequest {
  tts?: boolean;
  stt?: boolean;
  music?: boolean;
  image?: boolean;
}

export interface NewGameRequest extends FeatureRequest {
  type: "newGame";
  theme?: string;
  artStyle?: string;
  imageGeneration?: boolean;
  adventureOutline?: AdventureOutline | null;
}

export interface LoadGameRequest extends FeatureRequest {
  type: "loadGame";
  gameId: string;
}

/** Re-attach to a session after reconnecting; answered by sessionResumed or an error with details SESSION_EXPIRED. */
export interface ResumeSessionRequest {
  type: "resumeSession";
  sessionId: string;
}

export interface CommandRequest {
  type: "command";
  command: string;
}

export interface StreamCommandRequest {
  type: "streamCommand";
  command: string;
  imageGeneration?: boolean;
}

export interface AudioCommandRequest {
  type: "audioCommand";
  audio: string; // Base64 encoded audio
  imageGeneration?: boolean;
}

export interface ListGamesRequest {
  type: "listGames";
}

export interface PingMessage {
  type: "ping";
  timestamp?: number;
}

export type ClientMessage =
  | NewGameRequest
  | LoadGameRequest
  | ResumeSessionRequest
  | CommandRequest
  | StreamCommandRequest
  | AudioCommandRequest
  | ListGamesRequest
  | PingMessage;

// ============= Server Messages (received from server) =============

export interface SessionFeatureFlags {
  ttsEnabled: boolean;
  sttEnabled: boolean;
  imageEnabled: boolean;
  musicEnabled: boolean;
}

export interface ConnectedMessage {
  type: "connected";
  data: {
    message: string;
    version: string;
    serverInstanceId: string;
  };
}

export interface GameStartedMessage {
  type: "gameStarted";
  data: SessionFeatureFlags & {
    sessionId: string;
    gameId: string;
    text: string;
    messageIndex: number;
    scene?: SceneData;
    audio?: string;
    hasImage: boolean;
    hasMusic: boolean;
  };
}

/** The flags are the ones the session now uses: loading an open game applies the requested features to it. */
export interface GameLoadedMessage {
  type: "gameLoaded";
  data: SessionFeatureFlags & {
    sessionId: string;
    gameId: string;
    /** The session's message index, as in sessionResumed. */
    messageIndex: number;
    conversation: ConversationEntry[];
    currentLocation?: string;
    currentScene?: SceneData;
  };
}

export interface SessionResumedMessage {
  type: "sessionResumed";
  data: {
    sessionId: string;
    gameId: string;
    messageIndex: number;
  };
}

/** Another connection loaded this game and took its session: stop resuming it, and offer to load the game again. */
export interface SessionTakenOverMessage {
  type: "sessionTakenOver";
  data: {
    sessionId: string;
    gameId: string;
  };
}

export interface CommandResponseMessage {
  type: "commandResponse";
  data: {
    text: string;
    messageIndex: number;
    command: string;
    scene?: SceneData;
    audio?: string;
    hasImage: boolean;
    hasMusic: boolean;
  };
}

export interface TextChunkMessage {
  type: "textChunk";
  data: {
    text: string;
    chunkNumber: number;
  };
}

export interface StreamCompleteMessage {
  type: "streamComplete";
  data: {
    /** The final narration; replaces the streamed text. */
    text: string;
    messageIndex: number;
    totalChunks: number;
    duration: number;
    scene?: SceneData;
    audio?: string;
    hasImage: boolean;
    hasMusic: boolean;
  };
}

export interface TranscriptionMessage {
  type: "transcription";
  data: {
    text: string;
  };
}

export interface ImageReadyMessage {
  type: "imageReady";
  data: {
    messageIndex: number;
    image: string; // Base64 encoded image
  };
}

export interface MusicReadyMessage {
  type: "musicReady";
  data: {
    messageIndex: number;
    music: string; // Base64 encoded audio
    mood: string;
  };
}

/** Media announced with hasImage/hasMusic could not be produced. */
export interface MediaFailedMessage {
  type: "mediaFailed";
  data: {
    messageIndex: number;
    kind: "image" | "music";
  };
}

export interface GamesListMessage {
  type: "gamesList";
  data: {
    games: GameInfo[];
  };
}

export interface ErrorMessage {
  type: "error";
  data: {
    error: string;
    details?: string;
  };
}

/** `details` of the error answering resumeSession for a session that no longer exists. */
export const SESSION_EXPIRED = "SESSION_EXPIRED";

export interface PongMessage {
  type: "pong";
  data: {
    timestamp: number;
  };
}

export type ServerMessage =
  | ConnectedMessage
  | GameStartedMessage
  | GameLoadedMessage
  | SessionResumedMessage
  | SessionTakenOverMessage
  | CommandResponseMessage
  | TextChunkMessage
  | StreamCompleteMessage
  | TranscriptionMessage
  | ImageReadyMessage
  | MusicReadyMessage
  | MediaFailedMessage
  | GamesListMessage
  | ErrorMessage
  | PongMessage;

// ============= Data Types =============

export interface SceneData {
  locationName: string;
  exits: ExitData[];
  items: string[];
  npcs: string[];
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
