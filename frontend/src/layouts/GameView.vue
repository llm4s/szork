<template>
  <v-container fluid class="game-container pa-0">
    <!-- Intro Screen -->
    <div v-if="!gameStarted && !setupStarted && !selectionStarted" class="intro-screen">
      <div class="intro-content">
        <img src="/SZork_intro.webp" alt="Welcome to Szork" class="intro-image" />
        <v-btn size="x-large" class="begin-button begin-adventure-btn" @click="beginSelection">
          Begin Your Adventure
          <v-icon end>mdi-chevron-right</v-icon>
        </v-btn>
      </div>
    </div>

    <!-- Game Selection Screen -->
    <GameSelection
      v-else-if="selectionStarted && !setupStarted && !gameStarted"
      @start-new-game="startNewGame"
      @load-game="loadSelectedGame"
      @play-adventure="playAdventure"
    />

    <!-- Adventure Setup Screen -->
    <AdventureSetup
      v-else-if="setupStarted && !gameStarted"
      :previous="previousSetup"
      @adventure-ready="onAdventureReady"
      @back-to-selection="goBackToSelection"
    />

    <!-- Game Screen -->
    <v-row v-else class="game-row ma-0">
      <v-col cols="12" md="8" class="mx-auto game-col pa-0">
        <div class="game-wrapper">
          <v-card-title class="text-h5 game-title">
            <v-row align="center" justify="space-between">
              <v-col cols="auto">SZork - {{ title }}</v-col>
              <v-col cols="auto">
                <GameHeaderControls
                  v-model:image-generation="imageGenerationEnabled"
                  v-model:background-music="backgroundMusicEnabled"
                  v-model:narration="narrationEnabled"
                  :image-available="imageEnabled"
                  :music-available="musicEnabled"
                  :tts-available="ttsEnabled"
                  @back="backToSelection"
                />
              </v-col>
            </v-row>
          </v-card-title>

          <MessageList ref="messageList" :messages="messages" :loading="loading" :opening="openingText" />

          <!-- The connection dropped: reconnecting by itself, or given up until the player retries -->
          <v-alert
            v-if="connectionState === 'reconnecting' || connectionState === 'closed'"
            class="connection-alert mx-4 mb-2"
            :type="connectionState === 'reconnecting' ? 'info' : 'error'"
            variant="tonal"
            density="compact"
            role="status"
          >
            {{
              connectionState === "reconnecting"
                ? "Lost the connection to the server. Reconnecting..."
                : "Not connected to the server."
            }}
            <template v-if="connectionState === 'closed'" v-slot:append>
              <v-btn variant="text" size="small" class="retry-connection" @click="retryConnection">Retry</v-btn>
            </template>
          </v-alert>

          <!-- The adventure is over; the player may keep exploring -->
          <v-alert
            v-if="gameOutcome"
            class="game-ended mx-4 mb-2"
            :type="gameOutcome === 'completed' ? 'success' : 'warning'"
            variant="tonal"
            density="compact"
            :title="gameOutcome === 'completed' ? 'The End' : 'Game Over'"
          >
            {{
              gameOutcome === "completed"
                ? "You have completed the adventure. You can keep exploring, or start another."
                : "The adventure has ended in failure. You can keep exploring, or start another."
            }}
            <template v-slot:append>
              <v-btn variant="text" size="small" @click="backToSelection">Back to games</v-btn>
            </template>
          </v-alert>

          <div class="game-input-wrapper">
            <v-text-field
              v-model="userInput"
              @keyup.enter="sendCommand"
              placeholder="Enter your command..."
              aria-label="Your command"
              :maxlength="MAX_COMMAND_CHARS"
              :counter="nearCommandLimit ? MAX_COMMAND_CHARS : undefined"
              :persistent-counter="nearCommandLimit"
              :hide-details="!nearCommandLimit"
              :disabled="sessionTakenOver"
              variant="outlined"
              density="compact"
              class="game-input"
              autofocus
            >
              <template v-slot:append-inner>
                <v-tooltip text="Hold to capture speech" location="top">
                  <template v-slot:activator="{ props }">
                    <button
                      v-bind="props"
                      type="button"
                      aria-label="Hold to speak a command"
                      :aria-pressed="recording"
                      @keydown.space.prevent="startRecording"
                      @keydown.enter.prevent="startRecording"
                      @keyup.space.prevent="stopRecording"
                      @keyup.enter.prevent="stopRecording"
                      @blur="stopRecording"
                      @mousedown.prevent="startRecording"
                      @mouseup.prevent="stopRecording"
                      @mouseleave.prevent="stopRecording"
                      @touchstart.prevent="startRecording"
                      @touchend.prevent="stopRecording"
                      @touchcancel.prevent="stopRecording"
                      :disabled="loading || !sttEnabled || sessionTakenOver"
                      class="audio-record-btn mr-1"
                      :class="{ recording: recording }"
                    >
                      <v-icon>mdi-microphone</v-icon>
                    </button>
                  </template>
                </v-tooltip>
                <v-tooltip text="Submit text command" location="top">
                  <template v-slot:activator="{ props }">
                    <v-btn
                      v-bind="props"
                      @click="sendCommand"
                      color="primary"
                      variant="text"
                      icon="mdi-send"
                      aria-label="Send command"
                      :disabled="!userInput.trim() || loading || sessionTakenOver"
                      :loading="loading"
                    ></v-btn>
                  </template>
                </v-tooltip>
              </template>
            </v-text-field>
          </div>
        </div>
      </v-col>
    </v-row>
    <!-- Another tab or window loaded this game: this one stops playing it until the player takes it back -->
    <v-snackbar :model-value="sessionTakenOver && gameStarted" color="warning" :timeout="-1" location="top">
      This game is now being played in another tab or window.
      <template v-slot:actions>
        <v-btn variant="text" @click="takeBackSession">Play here</v-btn>
        <v-btn variant="text" @click="backToSelection">Back to games</v-btn>
      </template>
    </v-snackbar>
    <v-snackbar v-model="errorSnackbar" color="error" :timeout="6000" location="top">
      {{ errorMessage }}
      <template v-slot:actions>
        <v-btn variant="text" @click="errorSnackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import { defineComponent, ref, nextTick, onMounted, watch, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import AdventureSetup from "@/components/AdventureSetup.vue";
import GameSelection from "@/components/GameSelection.vue";
import GameHeaderControls from "@/components/GameHeaderControls.vue";
import MessageList from "@/components/MessageList.vue";
import { useWebSocketGame } from "@/composables/useWebSocketGame";
import { useAudioPlayback } from "@/composables/useAudioPlayback";
import { blobToBase64, useVoiceRecorder } from "@/composables/useVoiceRecorder";
import { MAX_COMMAND_CHARS, nearLimit } from "@/data/limits";
import type { AdventureConfig, AdventureOutline, AdventureSummary, StyleChoice, ThemeChoice } from "@/types/api";

/** What the player is told when the game server cannot be reached. */
const UNREACHABLE = "Could not reach the game server. Please try again.";

export default defineComponent({
  name: "GameView",
  components: {
    AdventureSetup,
    GameSelection,
    GameHeaderControls,
    MessageList,
  },
  props: {
    gameId: {
      type: String,
      default: null,
    },
  },
  setup(props) {
    const router = useRouter();
    const route = useRoute();

    // Use the WebSocket composable
    const {
      gameId: currentGameId,
      messages,
      connectionState,
      isStreaming,
      awaitingResponse,
      ttsEnabled,
      sttEnabled,
      musicEnabled,
      imageEnabled,
      connect,
      startNewGame: wsStartNewGame,
      loadGame: wsLoadGame,
      sendCommand: wsSendCommand,
      sendAudioCommand: wsSendAudioCommand,
      setScrollCallback,
      setPlayBackgroundMusicCallback,
      setPlayAudioNarrationCallback,
      clearMessages,
      addLocalMessage,
      loadFailed,
      startFailed,
      sessionTakenOver,
      gameDeleted,
      adventureTitle: serverTitle,
      offeredCommand,
      gameOutcome,
      takeBackSession,
      log: wsLog,
      errorSnackbar,
      errorMessage,
      showError,
    } = useWebSocketGame();
    const {
      narrationEnabled,
      backgroundMusicEnabled,
      playNarration,
      playBackgroundMusic,
      stopBackgroundMusic,
      stopAll,
    } = useAudioPlayback({ log: wsLog });

    // Local UI state
    const userInput = ref("");
    const messageList = ref<InstanceType<typeof MessageList>>();
    // Waiting for the response to a typed or spoken command
    const loading = computed(() => isStreaming.value || awaitingResponse.value);
    const gameStarted = ref(false);
    const setupStarted = ref(false);
    const selectionStarted = ref(false);
    const adventureTheme = ref<ThemeChoice | null>(null);
    const artStyle = ref<StyleChoice | null>(null);
    const adventureOutline = ref<AdventureOutline | null>(null);
    // The setup choices of the game being started, so a start that fails returns to them rather than to a fresh setup
    const previousSetup = ref<AdventureConfig | null>(null);
    // Shown while a game is opening and its first scene has not arrived
    const openingText = ref("Starting your adventure...");
    // The library adventure being started, if the game is of one
    const adventureId = ref<string | undefined>(undefined);
    // The title of the adventure being started, shown until the server confirms the game with its own
    const plannedTitle = ref<string | null>(null);
    const title = computed(() => serverTitle.value ?? plannedTitle.value ?? "Generative Adventuring");
    // Near the command length limit, the field shows how much is left
    const nearCommandLimit = computed(() => nearLimit(userInput.value.length, MAX_COMMAND_CHARS));
    const imageGenerationEnabled = ref(true);
    const speechInputRequested = ref(true);
    // The game this view last asked to load (gameLoaded may not have arrived yet)
    let requestedGameId: string | null = null;

    // Use the log function from the composable
    const log = wsLog;

    /** Show the latest message; unless forced, only when the player has not scrolled up to read. */
    const scrollToBottom = async (force = false) => {
      // The list may only appear on the next render (e.g. right after a game is loaded)
      await nextTick();
      await messageList.value?.scrollToBottom(force);
    };

    const beginSelection = () => {
      selectionStarted.value = true;
    };

    const startNewGame = () => {
      previousSetup.value = null;
      selectionStarted.value = false;
      setupStarted.value = true;
    };

    const loadSelectedGame = async (gameId: string) => {
      selectionStarted.value = false;
      await loadGame(gameId);
    };

    const backToSelection = () => {
      resetGame();
      // Clear the game ID from the URL if present
      if (route.params.gameId) {
        router.push("/");
      }
    };

    /** Leave the current game for the selection screen. */
    const resetGame = () => {
      stopAll();

      // Reset game state
      gameStarted.value = false;
      setupStarted.value = false;
      selectionStarted.value = true;
      clearMessages();
      requestedGameId = null;
      plannedTitle.value = null;
    };

    const goBackToSelection = () => {
      // Reset from AdventureSetup to GameSelection
      previousSetup.value = null;
      setupStarted.value = false;
      selectionStarted.value = true;
    };

    const onAdventureReady = (config: AdventureConfig) => {
      previousSetup.value = config;
      adventureTheme.value = config.theme;
      artStyle.value = config.style;
      adventureOutline.value = config.outline;
      adventureId.value = config.adventureId;
      imageGenerationEnabled.value = config.sessionFeatures.image;
      backgroundMusicEnabled.value = config.sessionFeatures.music;
      narrationEnabled.value = config.sessionFeatures.tts;
      speechInputRequested.value = config.sessionFeatures.stt;
      plannedTitle.value = config.outline?.title ?? null;
      gameStarted.value = true;
      setupStarted.value = false;
      nextTick(() => {
        startGame();
      });
    };

    /** Start a game of a ready-made adventure: the server has its outline, theme and art style. */
    const playAdventure = (adventure: AdventureSummary) => {
      previousSetup.value = null;
      adventureTheme.value = null;
      artStyle.value = null;
      adventureOutline.value = null;
      adventureId.value = adventure.id;
      plannedTitle.value = adventure.title;
      gameStarted.value = true;
      setupStarted.value = false;
      selectionStarted.value = false;
      nextTick(() => {
        startGame();
      });
    };

    /** Open a saved game; one that cannot be loaded returns to the selection screen with the error. */
    const loadGame = async (gameId: string) => {
      log(`Loading game: ${gameId}`);
      openingText.value = "Loading your adventure...";
      requestedGameId = gameId;
      stopBackgroundMusic();
      // Nothing of the game shown before stays while this one loads
      clearMessages();
      plannedTitle.value = null;
      try {
        // The server answers with gameLoaded, or an error that sets loadFailed
        await wsLoadGame(gameId);
      } catch (error) {
        log("Could not connect to load the game:", error);
        showError(UNREACHABLE);
        backToSelection();
        return;
      }
      gameStarted.value = true;
      setupStarted.value = false;
      selectionStarted.value = false;
      if (route.params.gameId !== gameId) router.push(`/game/${gameId}`);
      await scrollToBottom(true);
    };

    // A game that cannot be loaded (deleted, or an invalid link) returns to the selection screen
    watch(loadFailed, (failure) => {
      if (!failure) return;
      log("Error loading game:", failure);
      backToSelection();
    });

    /**
     * Leave a game that could not be started: a designed adventure returns to its setup with the choices
     * and the outline kept, so it can be started again without designing it again; a library one to the list.
     */
    const abandonStart = () => {
      const setup = previousSetup.value;
      resetGame();
      if (setup) {
        previousSetup.value = setup;
        selectionStarted.value = false;
        setupStarted.value = true;
      }
    };

    // The game was deleted (from another tab or window): back to the list, saying why
    watch(gameDeleted, (deleted) => {
      if (!deleted) return;
      log("The game being played was deleted");
      backToSelection();
      showError("This game was deleted, so it can no longer be played.");
    });

    // A command that never reached the server goes back in the command line, unless the player has typed another
    watch(offeredCommand, (command) => {
      if (command && !userInput.value) userInput.value = command;
    });

    watch(startFailed, (failure) => {
      if (!failure) return;
      log("Error starting game:", failure);
      abandonStart();
    });

    // Once a new game has an id, put it in the URL so a refresh returns to it
    watch(currentGameId, (id) => {
      if (id && gameStarted.value && route.params.gameId !== id) router.replace(`/game/${id}`);
    });

    // Both routes use this view, so browser back/forward changes the gameId prop without
    // remounting: load the game it names, or leave the game when it names none
    watch(
      () => props.gameId,
      (id) => {
        if (id) {
          if (id !== requestedGameId && id !== currentGameId.value) loadGame(id);
        } else if (gameStarted.value) {
          resetGame();
        }
      }
    );

    const startGame = async () => {
      try {
        log(
          `Starting game: theme ${adventureTheme.value?.id}, style ${artStyle.value?.id},`,
          adventureId.value ? `library adventure ${adventureId.value}` : `outline "${adventureOutline.value?.title}"`
        );

        // Clean up any existing audio when starting a new game
        stopBackgroundMusic();
        openingText.value = "Starting your adventure...";

        await wsStartNewGame({
          theme: adventureTheme.value,
          artStyle: artStyle.value?.id,
          imageGeneration: imageGenerationEnabled.value,
          outline: adventureOutline.value,
          features: {
            tts: narrationEnabled.value,
            music: backgroundMusicEnabled.value,
            stt: speechInputRequested.value,
          },
          adventureId: adventureId.value,
        });
      } catch (error) {
        log("Could not connect to start the game:", error);
        showError(UNREACHABLE);
        abandonStart();
      }
    };

    /** Try the connection again after the client gave up reconnecting; the session resumes when it opens. */
    const retryConnection = async () => {
      try {
        await connect();
      } catch (error) {
        log("Retrying the connection failed:", error);
        showError(UNREACHABLE);
      }
    };

    // Another tab now plays this game: stop its music and narration here
    watch(sessionTakenOver, (taken) => {
      if (taken) stopAll();
    });

    const sendCommand = async () => {
      const command = userInput.value.trim();
      // One command at a time, so responses cannot arrive out of order
      if (!command || loading.value) return;

      userInput.value = "";
      try {
        await wsSendCommand(command, imageGenerationEnabled.value);
        await scrollToBottom(true);
      } catch (error) {
        // Not sent: give the command back to the player to send again
        log("Error sending command:", error);
        if (!userInput.value) userInput.value = command;
        showError(UNREACHABLE);
      }
    };

    const sendAudioCommand = async (audioBlob: Blob) => {
      // Check if audio clip has content
      log("Audio blob size:", audioBlob.size, "bytes");
      if (audioBlob.size === 0) {
        addLocalMessage("Please hold the record button to record audio");
        return;
      }

      try {
        const base64Audio = await blobToBase64(audioBlob);
        // The transcription and response will come via WebSocket events
        await wsSendAudioCommand(base64Audio, imageGenerationEnabled.value, audioBlob.type);
      } catch (error) {
        addLocalMessage("Error sending the recording. Please try again.");
        log("Error sending audio:", error);
      } finally {
        await scrollToBottom(true);
      }
    };

    const { recording, startRecording, stopRecording } = useVoiceRecorder({
      onRecorded: sendAudioCommand,
      onError: () => addLocalMessage("Error: Could not access microphone. Please check permissions."),
      log,
    });

    onMounted(async () => {
      // Follow new text as it arrives, unless the player has scrolled up to read
      setScrollCallback(() => {
        scrollToBottom();
      });

      // Set up audio playback callbacks
      setPlayBackgroundMusicCallback(playBackgroundMusic);
      setPlayAudioNarrationCallback(playNarration);

      // Connect to WebSocket server
      try {
        await connect();
        log("WebSocket connected successfully");
      } catch (error) {
        // Not fatal here: each request connects again, and says so if it cannot
        log("Failed to connect to WebSocket:", error);
      }

      // Check if there's a game ID in the URL
      const gameIdFromRoute = props.gameId;
      // A navigation while connecting may already have loaded it
      if (gameIdFromRoute && gameIdFromRoute !== requestedGameId) {
        log(`Found game ID in URL: ${gameIdFromRoute}`);
        loadGame(gameIdFromRoute);
      } else {
        // No game ID, don't automatically start anything
        // The user will click "Begin Your Adventure" to go to selection screen
        log("No game ID in URL, waiting for user to begin");
      }
    });

    return {
      messages,
      connectionState,
      retryConnection,
      previousSetup,
      userInput,
      messageList,
      sendCommand,
      loading,
      recording,
      startRecording,
      stopRecording,
      narrationEnabled,
      gameStarted,
      setupStarted,
      selectionStarted,
      beginSelection,
      startNewGame,
      loadSelectedGame,
      playAdventure,
      openingText,
      backToSelection,
      goBackToSelection,
      onAdventureReady,
      backgroundMusicEnabled,
      loadGame,
      title,
      nearCommandLimit,
      MAX_COMMAND_CHARS,
      imageGenerationEnabled,
      // Feature flags from server
      ttsEnabled,
      sttEnabled,
      musicEnabled,
      imageEnabled,
      errorSnackbar,
      errorMessage,
      sessionTakenOver,
      gameOutcome,
      takeBackSession,
    };
  },
});
</script>

<style scoped>
.intro-screen {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  position: relative;
  overflow: hidden;
}

.intro-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3rem;
  animation: fadeIn 1s ease-in;
}

.intro-image {
  max-width: 90%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.8);
}

.begin-button {
  font-size: 1.2rem;
  animation: pulse-shadow 2s ease-in-out infinite;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  line-height: 1 !important;
}

.begin-adventure-btn {
  background-color: #000000 !important;
  color: #ffffff !important;
  border: 2px solid #ffffff !important;
  border-radius: 8px !important;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.5px;
  padding: 0 32px !important;
  min-height: 56px !important;
  height: 56px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  line-height: 1 !important;
  box-shadow:
    inset 2px 2px 4px rgba(255, 255, 255, 0.2),
    inset -2px -2px 4px rgba(0, 0, 0, 0.5),
    0 4px 8px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
}

.begin-adventure-btn:hover {
  background-color: #1a1a1a !important;
  border-color: #ffffff !important;
  box-shadow:
    inset 2px 2px 6px rgba(255, 255, 255, 0.3),
    inset -2px -2px 6px rgba(0, 0, 0, 0.6),
    0 6px 12px rgba(0, 0, 0, 0.4);
  transform: translateY(-2px);
}

.begin-adventure-btn:active {
  box-shadow:
    inset 2px 2px 4px rgba(0, 0, 0, 0.6),
    inset -2px -2px 4px rgba(255, 255, 255, 0.1),
    0 2px 4px rgba(0, 0, 0, 0.2);
  transform: translateY(0);
}

@keyframes pulse-shadow {
  0%,
  100% {
    box-shadow:
      inset 2px 2px 4px rgba(255, 255, 255, 0.2),
      inset -2px -2px 4px rgba(0, 0, 0, 0.5),
      0 4px 8px rgba(0, 0, 0, 0.3),
      0 0 20px rgba(255, 255, 255, 0.2);
  }
  50% {
    box-shadow:
      inset 2px 2px 4px rgba(255, 255, 255, 0.2),
      inset -2px -2px 4px rgba(0, 0, 0, 0.5),
      0 4px 8px rgba(0, 0, 0, 0.3),
      0 0 40px rgba(255, 255, 255, 0.4);
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.game-container {
  height: 100vh;
  background-color: #121212;
  display: flex;
}

.game-row {
  flex: 1;
  display: flex;
}

.game-col {
  display: flex;
}

.game-wrapper {
  width: 100%;
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 1rem;
}

.game-title {
  flex-shrink: 0;
  padding: 1rem;
  background-color: #1a1a1a;
  border-bottom: 1px solid #333;
}

.game-input-wrapper {
  flex-shrink: 0;
  background-color: #121212;
  padding: 0.5rem 0;
}

.game-input {
  font-family: "Courier New", Courier, monospace;
  background-color: #1e1e1e !important;
}

.audio-record-btn {
  background: transparent;
  border: none;
  padding: 8px;
  cursor: pointer;
  border-radius: 50%;
  transition: all 0.2s;
  color: #f44336;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.audio-record-btn:hover:not(:disabled) {
  background-color: rgba(244, 67, 54, 0.1);
}

.audio-record-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.audio-record-btn.recording {
  background-color: #f44336;
  color: white;
  animation: recording-pulse 1s ease-in-out infinite;
}

@keyframes recording-pulse {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.8;
    transform: scale(1.1);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
