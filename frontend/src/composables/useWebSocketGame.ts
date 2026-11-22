/**
 * Composable for WebSocket-based game communication
 */

import { ref, onUnmounted, nextTick, getCurrentInstance } from "vue";
import { WebSocketClient } from "@/services/WebSocketClient";
import { SESSION_EXPIRED } from "@/types/WebSocketProtocol";
import type { AdventureOutline } from "@/types/api";
import type {
  SceneData,
  ConversationEntry,
  FeatureRequest,
  SessionFeatureFlags,
  GameStartedMessage,
  GameLoadedMessage,
  SessionResumedMessage,
  SessionTakenOverMessage,
  CommandResponseMessage,
  TextChunkMessage,
  StreamCompleteMessage,
  TranscriptionMessage,
  ImageReadyMessage,
  MusicReadyMessage,
  MediaFailedMessage,
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
  // True from sending a command (typed or spoken) until its response or an error arrives
  const awaitingResponse = ref(false);
  const currentStreamingMessage = ref<GameMessage | null>(null);
  // Error toast state
  const errorSnackbar = ref(false);
  const errorMessage = ref("");
  // Set when a requested game could not be loaded
  const loadFailed = ref<string | null>(null);
  // Set when another tab or window loaded this game and took its session; cleared by loading it again
  const sessionTakenOver = ref(false);
  // Session feature flags, as confirmed by the server
  const ttsEnabled = ref(true);
  const sttEnabled = ref(true);
  const musicEnabled = ref(true);
  const imageEnabled = ref(true);

  let scrollCallback: (() => void) | null = null;
  let playBackgroundMusicCallback: ((musicBase64: string, mood: string) => void) | null = null;
  let playAudioNarrationCallback: ((audioBase64: string) => void) | null = null;
  let pendingLoad: string | null = null;
  // The server's message index after the last response (or load) we received; null until then.
  // A larger index on resume means we missed a response.
  let lastMessageIndex: number | null = null;
  // Set once the owning component unmounts: late callbacks must not open a new connection
  let disposed = false;

  const log = (message: string, ...args: unknown[]) => {
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

  /** The features this session has, to ask for again when the game is reloaded. */
  const currentFeatures = (): FeatureRequest => ({
    tts: ttsEnabled.value,
    stt: sttEnabled.value,
    music: musicEnabled.value,
    image: imageEnabled.value,
  });

  /** Ends the in-progress response (streamed or not), dropping a streamed one if nothing arrived. */
  const endStreaming = () => {
    awaitingResponse.value = false;
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

  /**
   * Connect to the WebSocket server. The client is reused, so a connection that dropped (and is
   * waiting to reconnect, or has run out of retries) reopens with its handlers and queued messages.
   */
  const connect = async (): Promise<void> => {
    if (disposed) return;
    let client = wsClient.value;
    if (!client) {
      const created = new WebSocketClient();
      setupMessageHandlers(created);
      created.onConnectionChange((connected) => {
        isConnected.value = connected;
        // Whenever a connection opens while we have a session, re-attach to it. This runs before
        // queued messages are sent, so a command typed while offline reaches the resumed session.
        // A session another tab took over is forgotten, so it is not taken back here.
        if (connected && sessionId.value) {
          log(`Connected; resuming session ${sessionId.value}`);
          created.resumeSession(sessionId.value);
        }
      });
      wsClient.value = created;
      client = created;
    }
    await client.connect();
  };

  /** Load the current game again, with the features this session had. */
  const reloadCurrentGame = () => {
    if (gameId.value) loadGame(gameId.value, currentFeatures());
  };

  const setupMessageHandlers = (client: WebSocketClient) => {
    client.on<GameStartedMessage>("gameStarted", ({ data }) => {
      sessionTakenOver.value = false;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      lastMessageIndex = data.messageIndex;
      applyFlags(data);
      messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    client.on<GameLoadedMessage>("gameLoaded", ({ data }) => {
      pendingLoad = null;
      sessionTakenOver.value = false;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      lastMessageIndex = data.messageIndex;
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
      log(`Session ${data.sessionId} resumed at message ${data.messageIndex}`);
      // The response we are waiting for was sent while we were disconnected, so it is lost:
      // stop waiting and load the game again to show it
      if (awaitingResponse.value && lastMessageIndex !== null && data.messageIndex > lastMessageIndex) {
        log("Missed the response while disconnected; reloading the game");
        endStreaming();
        reloadCurrentGame();
      }
    });

    client.on<SessionTakenOverMessage>("sessionTakenOver", ({ data }) => {
      // From a connection we have since left for another session: nothing to do
      if (data.sessionId !== sessionId.value) return;
      log(`Session ${data.sessionId} was taken over by another tab`);
      // Forget the session so reconnecting does not resume it (and take it back); keep the game,
      // so the player can choose to load it again here
      sessionId.value = null;
      endStreaming();
      sessionTakenOver.value = true;
    });

    client.on<CommandResponseMessage>("commandResponse", ({ data }) => {
      awaitingResponse.value = false;
      lastMessageIndex = data.messageIndex;
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
      lastMessageIndex = data.messageIndex;
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
      if (message?.hasImage) {
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

    client.on<ErrorMessage>("error", ({ data }) => {
      // The session ended while we were away (e.g. the server restarted): load the game again
      if (data.details === SESSION_EXPIRED) {
        if (gameId.value) {
          log(`Session expired; reloading game ${gameId.value}`);
          reloadCurrentGame();
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
    adventureOutline?: AdventureOutline | null,
    features?: FeatureRequest
  ) => {
    await connect();
    clearMessages();
    wsClient.value?.newGame(theme, artStyle, adventureOutline, {
      ...features,
      image: imageGeneration && (features?.image ?? true),
    });
  };

  /** Load the game again after another tab took its session, taking the session back. */
  const takeBackSession = () => {
    sessionTakenOver.value = false;
    reloadCurrentGame();
  };

  const loadGame = async (gameIdToLoad: string, features?: FeatureRequest) => {
    await connect();
    loadFailed.value = null;
    pendingLoad = gameIdToLoad;
    wsClient.value?.loadGame(gameIdToLoad, features);
  };

  /** Send a command; streaming shows the response as it is generated. */
  const sendCommand = async (command: string, streaming = true, imageGeneration = true) => {
    // This tab no longer plays the game; the server would refuse the command
    if (sessionTakenOver.value) return;
    await connect();
    messages.value.push({ text: command, isUser: true, timestamp: new Date() });

    awaitingResponse.value = true;
    if (streaming) {
      currentStreamingMessage.value = { text: "", isUser: false, timestamp: new Date(), isStreaming: true };
      messages.value.push(currentStreamingMessage.value);
      isStreaming.value = true;
      wsClient.value?.sendStreamCommand(command, imageGeneration && imageEnabled.value);
    } else {
      wsClient.value?.sendCommand(command);
    }
  };

  /** Send a recorded voice command; the transcription and response arrive as messages. */
  const sendAudioCommand = async (audioBase64: string, imageGeneration = true) => {
    if (sessionTakenOver.value) return;
    await connect();
    if (!wsClient.value) return;
    awaitingResponse.value = true;
    wsClient.value.sendAudio(audioBase64, imageGeneration && imageEnabled.value);
  };

  /** Forget the current game's messages and session (e.g. when returning to game selection). */
  const clearMessages = () => {
    endStreaming();
    messages.value = [];
    sessionId.value = null;
    gameId.value = null;
    lastMessageIndex = null;
    sessionTakenOver.value = false;
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

  // The composable can also be used outside a component (e.g. in tests)
  if (getCurrentInstance()) {
    onUnmounted(() => {
      disposed = true;
      disconnect();
    });
  }

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
    awaitingResponse,
    ttsEnabled,
    sttEnabled,
    musicEnabled,
    imageEnabled,
    loadFailed,
    sessionTakenOver,

    // Methods
    connect,
    disconnect,
    startNewGame,
    loadGame,
    takeBackSession,
    sendCommand,
    sendAudioCommand,
    clearMessages,
    addLocalMessage,
    setScrollCallback,
    setPlayBackgroundMusicCallback,
    setPlayAudioNarrationCallback,

    log,
    // Error toast
    errorSnackbar,
    errorMessage,
    showError,
  };
}
