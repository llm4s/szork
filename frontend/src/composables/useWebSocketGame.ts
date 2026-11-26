/**
 * Composable for WebSocket-based game communication
 */

import { ref, computed, onUnmounted, nextTick, getCurrentInstance } from "vue";
import { WebSocketClient, type ConnectionState } from "@/services/WebSocketClient";
import { SESSION_EXPIRED } from "@/types/WebSocketProtocol";
import type { AdventureOutline, ThemeChoice } from "@/types/api";
import type { SceneData, FeatureRequest, SessionFeatureFlags, GameOutcome } from "@/types/WebSocketProtocol";

export interface GameMessage {
  text: string;
  isUser: boolean;
  timestamp: Date;
  image?: string; // The picture's source: a library URL, or a data: URL of a picture made for this game
  imageLoading?: boolean;
  hasImage?: boolean;
  scene?: SceneData;
  /** The server's index for this response; media for it arrives tagged with the same index. */
  messageIndex?: number;
  isStreaming?: boolean;
}

/** The `error` the server answers a loadGame with when it cannot load the game. */
export const LOAD_FAILED = "Failed to load game";
/** The `error` the server answers a newGame with when it cannot start the game. */
export const START_FAILED = "Failed to start game";
/** The `error` answering a command sent to a session the server no longer has. */
const NO_ACTIVE_SESSION = "No active session";

/** What a new game is started with; every part is optional. */
export interface NewGameOptions {
  /** The theme: its prompt reaches the Dungeon Master, its name is what the game is listed as. */
  theme?: ThemeChoice | null;
  artStyle?: string;
  /** False asks for no scene images in this game. */
  imageGeneration?: boolean;
  /** The designed adventure, or null to play without one. */
  outline?: AdventureOutline | null;
  features?: FeatureRequest;
  /** A library adventure: the server plays its outline, theme and art style. */
  adventureId?: string;
}

/** Told to the player when a turn's command never reached the server (the connection dropped as it was sent). */
export const TURN_LOST = "Your last command did not reach the server. It is back in the command line to send again.";

/**
 * How long a turn may go without a word from the server (its first chunk, the next one, or the answer)
 * before the player is told it failed. Long enough for a slow model; a lost answer is not waited for forever.
 */
export const TURN_TIMEOUT_MS = 120_000;

/** What an <img> shows for a picture sent as a library `url` or as Base64 `image`; undefined when there is neither. */
function pictureSource(image?: string, url?: string): string | undefined {
  if (url) return url;
  if (!image) return undefined;
  // Pictures are JPEGs (which start "/9j/" in Base64) unless the provider's PNG could not be shrunk
  return `data:image/${image.startsWith("/9j/") ? "jpeg" : "png"};base64,${image}`;
}

export function useWebSocketGame() {
  // Not reactive: a Vue proxy would wrap the client's own socket, and its identity checks would fail
  let wsClient: WebSocketClient | null = null;
  const sessionId = ref<string | null>(null);
  const gameId = ref<string | null>(null);
  const messages = ref<GameMessage[]>([]);
  const connectionState = ref<ConnectionState>("closed");
  const isConnected = computed(() => connectionState.value === "open");
  const isStreaming = ref(false);
  // True from sending a command (typed or spoken) until its response or an error arrives
  const awaitingResponse = ref(false);
  const currentStreamingMessage = ref<GameMessage | null>(null);
  // Error toast state
  const errorSnackbar = ref(false);
  const errorMessage = ref("");
  // Set when a requested game could not be loaded
  const loadFailed = ref<string | null>(null);
  // Set when a new game could not be started
  const startFailed = ref<string | null>(null);
  // Set when another tab or window loaded this game and took its session; cleared by loading it again
  const sessionTakenOver = ref(false);
  // Set when the game being played was deleted (from another tab): the server has ended its session
  const gameDeleted = ref(false);
  // The adventure's title, as the server gave it with the game; null until a game has started or loaded
  const adventureTitle = ref<string | null>(null);
  // A command whose turn never reached the server, offered back to the player to send again
  const offeredCommand = ref<string | null>(null);
  // How the adventure ended, once it has (the player may keep exploring); null while it is still to be won or lost
  const gameOutcome = ref<GameOutcome | null>(null);
  // Session feature flags, as confirmed by the server
  const ttsEnabled = ref(true);
  const sttEnabled = ref(true);
  const musicEnabled = ref(true);
  const imageEnabled = ref(true);

  let scrollCallback: (() => void) | null = null;
  let playBackgroundMusicCallback: ((musicSource: string, mood: string) => void) | null = null;
  let playAudioNarrationCallback: ((audioBase64: string) => void) | null = null;
  // From asking for a game (new or loaded) until leaving it: messages about a game are ignored outside one,
  // so a late answer or picture after the player went back to the menu changes nothing
  let playing = false;
  // The game being loaded (and the features asked for), until gameLoaded or an error answers
  let pendingLoad: { gameId: string; features?: FeatureRequest } | null = null;
  let pendingStart = false;
  // The server's message index after the last response (or load) we received; null until then.
  // A larger index on resume means we missed a response.
  let lastMessageIndex: number | null = null;
  // Sends the turn being waited for again (it went to a session that has since expired)
  let pendingTurn: (() => Promise<void>) | null = null;
  // The text of the command being waited for: the typed command, or a spoken one's transcription
  let pendingCommand: string | null = null;
  // True from asking to resume the session until the answer (or until the connection drops)
  let resuming = false;
  // Set when the turn's command reaches the server after the resume (it was queued while offline, or sent while
  // the resume was being answered): then the server not knowing of a turn does not mean the turn was lost
  let turnFollowsResume = false;
  let turnTimer: ReturnType<typeof setTimeout> | null = null;
  // Set while the game is reloaded after its session expired: the turn sent to the expired session is
  // answered with "No active session", and is sent again (unless the save shows it was played) once loaded
  let reloadingExpired = false;
  let resendAfterReload: (() => Promise<void>) | null = null;
  // Set once the owning component unmounts: late callbacks must not open a new connection
  let disposed = false;

  const log = import.meta.env.DEV
    ? (message: string, ...args: unknown[]) => console.log(`[Game] ${message}`, ...args)
    : () => {};

  const scroll = () => {
    const callback = scrollCallback;
    if (callback) nextTick(callback);
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

  const clearTurnTimer = () => {
    if (turnTimer) clearTimeout(turnTimer);
    turnTimer = null;
  };

  /** (Re)start the wait for the server's next word on the current turn. */
  const armTurnTimer = () => {
    clearTurnTimer();
    turnTimer = setTimeout(() => {
      turnTimer = null;
      if (awaitingResponse.value) failTurn("The server did not answer. Please try again.");
    }, TURN_TIMEOUT_MS);
  };

  /** Starts waiting for the answer to a command (`command`, unless it is a recording); `resend` sends it again. */
  const startTurn = (resend: () => Promise<void>, command: string | null) => {
    awaitingResponse.value = true;
    pendingTurn = resend;
    pendingCommand = command;
    turnFollowsResume = resuming;
    armTurnTimer();
  };

  /** Ends the in-progress response (streamed or not), dropping a streamed one if nothing arrived. */
  const endStreaming = () => {
    awaitingResponse.value = false;
    pendingTurn = null;
    pendingCommand = null;
    clearTurnTimer();
    const streaming = currentStreamingMessage.value;
    if (streaming) {
      streaming.isStreaming = false;
      if (!streaming.text) messages.value = messages.value.filter((m) => m !== streaming);
    }
    currentStreamingMessage.value = null;
    isStreaming.value = false;
  };

  /** Ends the turn being waited for, telling the player why. */
  const failTurn = (text: string) => {
    endStreaming();
    messages.value.push({ text: `Error: ${text}`, isUser: false, timestamp: new Date() });
    scroll();
    showError(text);
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
    let client = wsClient;
    if (!client) {
      const created = new WebSocketClient();
      setupMessageHandlers(created);
      created.onConnectionChange((state, reconnected) => {
        connectionState.value = state;
        if (state !== "open") resuming = false;
        // Whenever a connection opens while we have a session, re-attach to it. This runs before
        // queued messages are sent, so a command typed while offline reaches the resumed session.
        // A session another tab took over is forgotten, so it is not taken back here.
        if (state === "open" && sessionId.value) {
          log(`Connected; resuming session ${sessionId.value}`);
          turnFollowsResume = awaitingResponse.value && created.hasQueued("streamCommand", "command", "audioCommand");
          resuming = true;
          created.resumeSession(sessionId.value);
        }
        // A load sent on the connection that dropped may never have arrived: ask again
        if (state === "open" && reconnected && pendingLoad && !created.hasQueued("loadGame")) {
          log(`Reconnected while loading; asking for game ${pendingLoad.gameId} again`);
          created.loadGame(pendingLoad.gameId, pendingLoad.features);
        }
        // The client has given up reconnecting: the answer being waited for will not come
        if (state === "closed" && awaitingResponse.value && !disposed) {
          failTurn("Lost the connection to the server.");
        }
      });
      wsClient = created;
      client = created;
    }
    await client.connect();
  };

  /** Load the current game again, with the features this session had. */
  const reloadCurrentGame = () => {
    if (gameId.value) loadGame(gameId.value, currentFeatures()).catch((error) => log("Reload failed:", error));
  };

  const setupMessageHandlers = (client: WebSocketClient) => {
    /** Handle a message about the game being played, ignoring it when no game is. */
    const onGame: WebSocketClient["on"] = (type, handler) =>
      client.on(type, (message) => {
        if (playing) handler(message);
        else log(`Ignoring ${type}: no game is being played`);
      });

    onGame("gameStarted", ({ data }) => {
      pendingStart = false;
      sessionTakenOver.value = false;
      gameDeleted.value = false;
      adventureTitle.value = data.title;
      gameOutcome.value = null;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      lastMessageIndex = data.messageIndex;
      applyFlags(data);
      messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    onGame("gameLoaded", ({ data }) => {
      // The turn sent to an expired session is sent again, unless the save shows it was played
      const resend = resendAfterReload && (lastMessageIndex === null || data.messageIndex <= lastMessageIndex);
      const turn = resendAfterReload;
      resendAfterReload = null;
      reloadingExpired = false;
      pendingLoad = null;
      sessionTakenOver.value = false;
      gameDeleted.value = false;
      adventureTitle.value = data.title;
      // The server says so again, after this message, if the loaded game has ended
      gameOutcome.value = null;
      sessionId.value = data.sessionId;
      gameId.value = data.gameId;
      lastMessageIndex = data.messageIndex;
      applyFlags(data);
      endStreaming();
      messages.value = data.conversation.map((entry) => ({
        text: entry.content,
        isUser: entry.role === "user",
        timestamp: new Date(),
        image: pictureSource(entry.image, entry.url),
      }));
      const lastMessage = messages.value[messages.value.length - 1];
      if (lastMessage && !lastMessage.isUser) {
        if (data.currentScene) lastMessage.scene = data.currentScene;
        // The latest answer's picture may still be being drawn: it arrives tagged with this index
        lastMessage.messageIndex = data.messageIndex;
        lastMessage.hasImage = true;
      }
      scroll();
      if (resend && turn) {
        log("Sending the command the expired session did not answer again");
        turn().catch((error) => log("Could not send the command again:", error));
      }
    });

    onGame("sessionResumed", ({ data }) => {
      log(`Session ${data.sessionId} resumed at message ${data.messageIndex}`);
      // A picture sent while we were disconnected is lost: stop showing it as on its way (one still
      // being drawn is shown when it arrives)
      messages.value.forEach((m) => (m.imageLoading = false));
      // A response sent while we were disconnected is lost: stop waiting and load the game again to show it
      if (lastMessageIndex !== null && data.messageIndex > lastMessageIndex) {
        log("Missed a response while disconnected; reloading the game");
        endStreaming();
        reloadCurrentGame();
      } else if (awaitingResponse.value) {
        if (data.turnInProgress || turnFollowsResume) {
          // The turn is still being played (or its command is on its way now): wait for it afresh
          armTurnTimer();
        } else {
          // The server has no turn and sent no answer: the command was lost with the connection
          log("The command being waited for never reached the server");
          const command = pendingCommand;
          failTurn(TURN_LOST);
          if (command) offeredCommand.value = command;
        }
      }
      resuming = false;
      turnFollowsResume = false;
    });

    onGame("gameDeleted", ({ data }) => {
      if (data.gameId !== gameId.value) return;
      log(`Game ${data.gameId} was deleted`);
      // Its session is gone: forget it, so reconnecting does not try to resume it
      sessionId.value = null;
      endStreaming();
      gameDeleted.value = true;
    });

    onGame("gameEnded", ({ data }) => {
      log(`The adventure is over: ${data.outcome}`);
      gameOutcome.value = data.outcome;
      scroll();
    });

    onGame("sessionTakenOver", ({ data }) => {
      // From a connection we have since left for another session: nothing to do
      if (data.sessionId !== sessionId.value) return;
      log(`Session ${data.sessionId} was taken over by another tab`);
      // Forget the session so reconnecting does not resume it (and take it back); keep the game,
      // so the player can choose to load it again here
      sessionId.value = null;
      endStreaming();
      sessionTakenOver.value = true;
    });

    onGame("commandResponse", ({ data }) => {
      endStreaming();
      lastMessageIndex = data.messageIndex;
      messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    onGame("textChunk", ({ data }) => {
      if (!currentStreamingMessage.value) return;
      armTurnTimer();
      currentStreamingMessage.value.text += data.text;
      scroll();
    });

    onGame("streamComplete", ({ data }) => {
      lastMessageIndex = data.messageIndex;
      const streaming = currentStreamingMessage.value;
      if (streaming) {
        streaming.text = data.text;
        streaming.scene = data.scene;
        streaming.messageIndex = data.messageIndex;
        streaming.hasImage = data.hasImage;
        streaming.imageLoading = data.hasImage;
      } else {
        // The answer to a turn the player stopped waiting for: show it after all
        messages.value.push(responseMessage(data.text, data.messageIndex, data.hasImage, data.scene));
      }
      endStreaming();
      if (data.audio) playAudioNarration(data.audio);
      scroll();
    });

    onGame("transcription", ({ data }) => {
      if (awaitingResponse.value) {
        armTurnTimer();
        pendingCommand = data.text;
      }
      messages.value.push({ text: data.text, isUser: true, timestamp: new Date() });
      scroll();
    });

    onGame("imageReady", ({ data }) => {
      const message = messages.value.find((m) => m.messageIndex === data.messageIndex);
      if (message?.hasImage) {
        message.image = pictureSource(data.image, data.url);
        message.imageLoading = false;
      }
    });

    onGame("musicReady", ({ data }) => {
      const source = data.url ?? (data.music ? `data:audio/mpeg;base64,${data.music}` : undefined);
      // Played, not kept: holding every track's base64 would grow memory for the whole session
      if (source && playBackgroundMusicCallback) playBackgroundMusicCallback(source, data.mood);
    });

    onGame("mediaFailed", ({ data }) => {
      if (data.kind === "image") {
        const message = messages.value.find((m) => m.messageIndex === data.messageIndex);
        if (message) message.imageLoading = false;
      }
    });

    onGame("error", ({ data }) => {
      // The session ended while we were away (e.g. the server restarted): load the game again
      if (data.details === SESSION_EXPIRED) {
        resuming = false;
        if (gameId.value) {
          log(`Session expired; reloading game ${gameId.value}`);
          // A command in flight went to the expired session: send it again once the game is loaded
          resendAfterReload = awaitingResponse.value ? pendingTurn : null;
          reloadingExpired = true;
          endStreaming();
          reloadCurrentGame();
        }
        return;
      }
      // The answer to a command queued behind the resume, which the reload sends again
      if (reloadingExpired && data.error === NO_ACTIVE_SESSION) return;

      const text = data.details ? `${data.error}: ${data.details}` : data.error;
      if (pendingLoad && data.error === LOAD_FAILED) {
        pendingLoad = null;
        reloadingExpired = false;
        resendAfterReload = null;
        endStreaming();
        loadFailed.value = text;
        showError(text);
      } else if (pendingStart && data.error === START_FAILED) {
        pendingStart = false;
        startFailed.value = text;
        showError(text);
      } else {
        failTurn(text);
      }
    });
  };

  const startNewGame = async (options: NewGameOptions = {}) => {
    await connect();
    clearMessages();
    playing = true;
    pendingStart = true;
    startFailed.value = null;
    const { theme, artStyle, imageGeneration = true, outline, features, adventureId } = options;
    wsClient?.newGame({
      theme: theme?.prompt,
      themeName: theme?.name,
      artStyle,
      adventureOutline: outline,
      adventureId,
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
    playing = true;
    loadFailed.value = null;
    pendingLoad = { gameId: gameIdToLoad, features };
    wsClient?.loadGame(gameIdToLoad, features);
  };

  /** Send a typed command; the response streams in as it is generated. */
  const sendCommand = async (command: string, imageGeneration = true) => {
    // This tab no longer plays the game; the server would refuse the command
    if (sessionTakenOver.value) return;
    await connect();
    messages.value.push({ text: command, isUser: true, timestamp: new Date() });

    offeredCommand.value = null;
    startTurn(() => sendCommand(command, imageGeneration), command);
    currentStreamingMessage.value = { text: "", isUser: false, timestamp: new Date(), isStreaming: true };
    messages.value.push(currentStreamingMessage.value);
    isStreaming.value = true;
    wsClient?.sendStreamCommand(command, imageGeneration && imageEnabled.value);
  };

  /**
   * Send a recorded voice command, recorded as `mimeType` (the recorder's format); the transcription and
   * response arrive as messages.
   */
  const sendAudioCommand = async (audioBase64: string, imageGeneration = true, mimeType?: string) => {
    if (sessionTakenOver.value) return;
    await connect();
    if (!wsClient) return;
    startTurn(() => sendAudioCommand(audioBase64, imageGeneration, mimeType), null);
    wsClient.sendAudio(audioBase64, imageGeneration && imageEnabled.value, mimeType || undefined);
  };

  /** Forget the current game's messages and session (e.g. when returning to game selection). */
  const clearMessages = () => {
    endStreaming();
    playing = false;
    pendingLoad = null;
    pendingStart = false;
    reloadingExpired = false;
    resendAfterReload = null;
    messages.value = [];
    sessionId.value = null;
    gameId.value = null;
    lastMessageIndex = null;
    sessionTakenOver.value = false;
    gameDeleted.value = false;
    adventureTitle.value = null;
    offeredCommand.value = null;
    gameOutcome.value = null;
  };

  /** Add a local message (not from the server), e.g. a microphone error. */
  const addLocalMessage = (text: string) => {
    messages.value.push({ text, isUser: false, timestamp: new Date() });
    scroll();
  };

  const disconnect = () => {
    clearTurnTimer();
    wsClient?.disconnect();
    wsClient = null;
    connectionState.value = "closed";
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

  const setPlayBackgroundMusicCallback = (callback: (musicSource: string, mood: string) => void) => {
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
    connectionState,
    isConnected,
    isStreaming,
    awaitingResponse,
    ttsEnabled,
    sttEnabled,
    musicEnabled,
    imageEnabled,
    loadFailed,
    startFailed,
    sessionTakenOver,
    gameDeleted,
    adventureTitle,
    offeredCommand,
    gameOutcome,

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
