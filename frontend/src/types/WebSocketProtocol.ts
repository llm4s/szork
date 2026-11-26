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
  /** The theme's prompt: what the Dungeon Master is told the adventure is about (a custom theme's description). */
  theme?: string;
  /** The theme's display name, what the game is listed and saved as (the prompt when absent). */
  themeName?: string;
  artStyle?: string;
  adventureOutline?: AdventureOutline | null;
  /** A library adventure to play: the server uses its outline, theme and art style. */
  adventureId?: string;
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
  /** The recorder's format (MediaRecorder.mimeType, e.g. "audio/mp4" in Safari); WebM when absent. */
  mimeType?: string;
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
    /** The adventure's title, or "Untitled Adventure" for a game without a designed adventure. */
    title: string;
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
    /** The adventure's title, or "Untitled Adventure" for a game without a designed adventure. */
    title: string;
    /** The session's message index, as in sessionResumed. */
    messageIndex: number;
    conversation: ConversationEntry[];
    currentLocation?: string;
    currentScene?: SceneData;
  };
}

/**
 * Answers resumeSession. A `messageIndex` larger than the client's last means it missed a response (check that
 * first). `turnInProgress` says whether a turn is running or queued on the server: if so its answer is still to come;
 * if not, a turn the client is waiting for never reached the server.
 */
export interface SessionResumedMessage {
  type: "sessionResumed";
  data: {
    sessionId: string;
    gameId: string;
    messageIndex: number;
    turnInProgress: boolean;
  };
}

/** The game this connection plays was deleted: its session has ended, and commands for it are refused. */
export interface GameDeletedMessage {
  type: "gameDeleted";
  data: {
    gameId: string;
  };
}

/**
 * The adventure is over: the main quest was completed or failed. Follows the response of the turn that ended it, and
 * gameLoaded or sessionResumed for a game that had already ended. The player may keep exploring.
 */
export interface GameEndedMessage {
  type: "gameEnded";
  data: {
    messageIndex: number;
    outcome: GameOutcome;
  };
}

export type GameOutcome = "completed" | "failed";

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
    /** Base64 JPEG (PNG if the provider's picture could not be shrunk), when the picture was made for this game. */
    image?: string;
    /** Where to fetch the picture, when it is the adventure library's. */
    url?: string;
  };
}

export interface MusicReadyMessage {
  type: "musicReady";
  data: {
    messageIndex: number;
    mood: string;
    /** Base64 MP3, when the track was made for this game. */
    music?: string;
    /** Where to fetch the track, when it is the adventure library's. */
    url?: string;
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
  | GameDeletedMessage
  | GameEndedMessage
  | CommandResponseMessage
  | TextChunkMessage
  | StreamCompleteMessage
  | TranscriptionMessage
  | ImageReadyMessage
  | MusicReadyMessage
  | MediaFailedMessage
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

/** A line of a loaded game's transcript: the player's command ("user") or the Dungeon Master's narration. */
export interface ConversationEntry {
  role: "user" | "assistant";
  content: string;
  /** The picture the line was shown with: Base64 (made for this game) or a library URL. */
  image?: string;
  url?: string;
}
