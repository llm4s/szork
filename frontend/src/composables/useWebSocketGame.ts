/**
 * Composable for WebSocket-based game communication
 */

import { ref, onUnmounted, nextTick } from "vue";
import { WebSocketClient } from "@/services/WebSocketClient";
import { SESSION_EXPIRED } from "@/types/WebSocketProtocol";
import type {
  SceneData,
  ConversationEntry,
  GameInfo,
  FeatureRequest,
  SessionFeatureFlags,
  GameStartedMessage,
  GameLoadedMessage,
  SessionResumedMessage,
  CommandResponseMessage,
  TextChunkMessage,
  StreamCompleteMessage,
  TranscriptionMessage,
  ImageReadyMessage,
  MusicReadyMessage,
  MediaFailedMessage,
  GamesListMessage,
  ErrorMessage,
} from "@/types/WebSocketProtocol";

export interface GameMessage {
  text: string;
  isUser: boolean;
  timestamp: Date;
  image?: string; // Base64 image data
  imageLoading?: boolean;
  hasImage?: boolean;
  scene?: SceneData;
  /** The server's index for this response; media for it arrives tagged with the same index. */
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
  // Error toast state
  const errorSnackbar = ref(false);
  const errorMessage = ref("");
  // Set when a requested game could not be loaded
  const loadFailed = ref<string | null>(null);
  // Session feature flags, as confirmed by the server
  const ttsEnabled = ref(true);
  const sttEnabled = ref(true);
  const musicEnabled = ref(true);
  const imageEnabled = ref(true);

  let scrollCallback: (() => void) | null = null;
  let playBackgroundMusicCallback: ((musicBase64: string, mood: string) => void) | null = null;
  let playAudioNarrationCallback: ((audioBase64: string) => void) | null = null;
  let pendingLoad: string | null = null;

  const log = (message: string, ...args: any[]) => {
    console.log(`[Game] ${message}`, ...args);
  };

  const scroll = () => {
    if (scrollCallback) nextTick(() => scrollCallback!());
  };

  const showError = (text: string) => {
    errorMessage.value = text;
    errorSnackbar.value = true;
  };

  const applyFlags = (flags: SessionFeatureFlags) => {
    ttsEnabled.value = flags.ttsEnabled;
    sttEnabled.value = flags.sttEnabled;
    musicEnabled.value = flags.musicEnabled;
    imageEnabled.value = flags.imageEnabled;
  };

  /** Ends the in-progress streamed response, dropping it if nothing arrived. */
  const endStreaming = () => {
    const streaming = currentStreamingMessage.value;
    if (streaming) {
      streaming.isStreaming = false;
      if (!streaming.text) messages.value = messages.value.filter((m) => m !== streaming);
    }
    currentStreamingMessage.value = null;
    isStreaming.value = false;
  };

  const responseMessage = (text: string, messageIndex: number, hasImage: boolean, scene?: SceneData): GameMessage => ({
    text,
    isUser: false,
    timestamp: new Date(),
    scene,
    messageIndex,
    hasImage,
    imageLoading: hasImage,
  });

  /** Connect to the WebSocket server (replacing any previous client). */
  const connect = async (): Promise<void> => {
    if (wsClient.value?.connected) return;
    wsClient.value?.disconnect();

    const client = new WebSocketClient();
    wsClient.value = client;
    setupMessageHandlers(client);
    client.onConnectionChange((connected, reconnected) => {
      isConnected.value = connected;
      // After a dropped connection, re-attach to the game in progress
      if (connected && reconnected && sessionId.value) {
        log(`Reconnected; resuming session ${sessionId.value}`);
        client.resumeSession(sessionId.value);
      }
    });
    await client.connect();
  };

  const setupMessageHandlers = (client: WebSocketClient) => {
    client.on<GameStartedMessage>("gameStarted", ({ data }) => {
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      applyFlags(data);
      messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    client.on<GameLoadedMessage>("gameLoaded", ({ data }) => {
      pendingLoad = null;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      applyFlags(data);
      endStreaming();
      // History has no media to attach, so its entries carry no message index
      messages.value = data.conversation.map((entry: ConversationEntry) => ({
        text: entry.content,
        isUser: entry.role === "user",
        timestamp: new Date(),
      }));
      const lastMessage = messages.value[messages.value.length - 1];
      if (data.currentScene && lastMessage && !lastMessage.isUser) lastMessage.scene = data.currentScene;
      scroll();
    });

    client.on<SessionResumedMessage>("sessionResumed", ({ data }) => {
      log(`Session ${data.sessionId} resumed`);
    });

    client.on<CommandResponseMessage>("commandResponse", ({ data }) => {
      messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    client.on<TextChunkMessage>("textChunk", ({ data }) => {
      if (!currentStreamingMessage.value) return;
      currentStreamingMessage.value.text += data.text;
      scroll();
    });

    client.on<StreamCompleteMessage>("streamComplete", ({ data }) => {
      const streaming = currentStreamingMessage.value;
      if (streaming) {
        streaming.text = data.text;
        streaming.scene = data.scene;
        streaming.messageIndex = data.messageIndex;
        streaming.hasImage = data.hasImage;
        streaming.imageLoading = data.hasImage;
      }
      endStreaming();
      if (data.audio) playAudioNarration(data.audio);
    });

    client.on<TranscriptionMessage>("transcription", ({ data }) => {
      messages.value.push({ text: data.text, isUser: true, timestamp: new Date() });
      scroll();
    });

    client.on<ImageReadyMessage>("imageReady", ({ data }) => {
      const message = messages.value.find((m) => m.messageIndex === data.messageIndex);
      if (message) {
        message.image = data.image;
        message.imageLoading = false;
      }
    });

    client.on<MusicReadyMessage>("musicReady", ({ data }) => {
      // Played, not kept: holding every track's base64 would grow memory for the whole session
      if (playBackgroundMusicCallback) playBackgroundMusicCallback(data.music, data.mood);
    });

    client.on<MediaFailedMessage>("mediaFailed", ({ data }) => {
      if (data.kind === "image") {
        const message = messages.value.find((m) => m.messageIndex === data.messageIndex);
        if (message) message.imageLoading = false;
      }
    });

    client.on<GamesListMessage>("gamesList", ({ data }) => {
      savedGames.value = data.games;
    });

    client.on<ErrorMessage>("error", ({ data }) => {
      // The session ended while we were away (e.g. the server restarted): load the game again
      if (data.details === SESSION_EXPIRED) {
        if (gameId.value) {
          log(`Session expired; reloading game ${gameId.value}`);
          loadGame(gameId.value);
        }
        return;
      }

      endStreaming();
      const text = data.details ? `${data.error}: ${data.details}` : data.error;
      if (pendingLoad) {
        pendingLoad = null;
        loadFailed.value = text;
      } else {
        messages.value.push({ text: `Error: ${text}`, isUser: false, timestamp: new Date() });
        scroll();
      }
      showError(text);
    });
  };

  const startNewGame = async (
    theme?: string,
    artStyle?: string,
    imageGeneration = true,
    adventureOutline?: any,
    features?: FeatureRequest
  ) => {
    await connect();
    clearMessages();
    wsClient.value?.newGame(theme, artStyle, imageGeneration, adventureOutline, {
      ...features,
      image: imageGeneration && (features?.image ?? true),
    });
  };

  const loadGame = async (gameIdToLoad: string, features?: FeatureRequest) => {
    await connect();
    loadFailed.value = null;
    pendingLoad = gameIdToLoad;
    wsClient.value?.loadGame(gameIdToLoad, features);
  };

  /** Send a command; streaming shows the response as it is generated. */
  const sendCommand = async (command: string, streaming = true, imageGeneration = true) => {
    await connect();
    messages.value.push({ text: command, isUser: true, timestamp: new Date() });

    if (streaming) {
      currentStreamingMessage.value = { text: "", isUser: false, timestamp: new Date(), isStreaming: true };
      messages.value.push(currentStreamingMessage.value);
      isStreaming.value = true;
      wsClient.value?.sendStreamCommand(command, imageGeneration && imageEnabled.value);
    } else {
      wsClient.value?.sendCommand(command);
    }
  };

  const sendAudioCommand = async (audioBase64: string) => {
    await connect();
    wsClient.value?.sendAudio(audioBase64);
  };

  const getSavedGames = async () => {
    await connect();
    wsClient.value?.listGames();
  };

  /** Forget the current game's messages and session (e.g. when returning to game selection). */
  const clearMessages = () => {
    endStreaming();
    messages.value = [];
    sessionId.value = null;
    gameId.value = null;
  };

  /** Add a local message (not from the server), e.g. a microphone error. */
  const addLocalMessage = (text: string) => {
    messages.value.push({ text, isUser: false, timestamp: new Date() });
    scroll();
  };

  const disconnect = () => {
    wsClient.value?.disconnect();
    wsClient.value = null;
    isConnected.value = false;
  };

  const playAudioNarration = (audioBase64: string) => {
    if (playAudioNarrationCallback) playAudioNarrationCallback(audioBase64);
  };

  onUnmounted(() => {
    disconnect();
  });

  const setScrollCallback = (callback: () => void) => {
    scrollCallback = callback;
  };

  const setPlayBackgroundMusicCallback = (callback: (musicBase64: string, mood: string) => void) => {
    playBackgroundMusicCallback = callback;
  };

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
    ttsEnabled,
    sttEnabled,
    musicEnabled,
    imageEnabled,
    loadFailed,

    // Methods
    connect,
    disconnect,
    startNewGame,
    loadGame,
    sendCommand,
    sendAudioCommand,
    getSavedGames,
    clearMessages,
    addLocalMessage,
    setScrollCallback,
    setPlayBackgroundMusicCallback,
    setPlayAudioNarrationCallback,

    log,
    // Error toast
    errorSnackbar,
    errorMessage,
  };
}
