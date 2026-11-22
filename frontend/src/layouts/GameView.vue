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
    />

    <!-- Adventure Setup Screen -->
    <AdventureSetup
      v-else-if="setupStarted && !gameStarted"
      @adventure-ready="onAdventureReady"
      @back-to-selection="goBackToSelection"
    />

    <!-- Game Screen -->
    <v-row v-else class="game-row ma-0">
      <v-col cols="12" md="8" class="mx-auto game-col pa-0">
        <div class="game-wrapper">
          <v-card-title class="text-h5 game-title">
            <v-row align="center" justify="space-between">
              <v-col cols="auto">SZork - {{ adventureTitle }}</v-col>
              <v-col cols="auto">
                <div class="audio-controls">
                  <v-tooltip text="Back to Adventure Selection" location="bottom">
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="backToSelection"
                        color="warning"
                        variant="outlined"
                        icon
                        size="small"
                        class="mr-2"
                      >
                        <v-icon>mdi-arrow-left</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip
                    :text="
                      imageEnabled
                        ? imageGenerationEnabled
                          ? 'Disable image generation'
                          : 'Enable image generation'
                        : 'Image generation disabled by server'
                    "
                    location="bottom"
                  >
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="imageEnabled && (imageGenerationEnabled = !imageGenerationEnabled)"
                        :color="imageGenerationEnabled && imageEnabled ? 'info' : 'grey'"
                        :variant="imageGenerationEnabled && imageEnabled ? 'flat' : 'outlined'"
                        :disabled="!imageEnabled"
                        icon
                        size="small"
                        class="mr-2"
                      >
                        <v-icon>{{ imageGenerationEnabled ? "mdi-image" : "mdi-image-off" }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip
                    :text="
                      musicEnabled
                        ? backgroundMusicEnabled
                          ? 'Disable background music'
                          : 'Enable background music'
                        : 'Music disabled by server'
                    "
                    location="bottom"
                  >
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="musicEnabled && (backgroundMusicEnabled = !backgroundMusicEnabled)"
                        :color="backgroundMusicEnabled && musicEnabled ? 'primary' : 'grey'"
                        :variant="backgroundMusicEnabled && musicEnabled ? 'flat' : 'outlined'"
                        :disabled="!musicEnabled"
                        icon
                        size="small"
                        class="mr-2"
                      >
                        <v-icon>{{ backgroundMusicEnabled ? "mdi-music" : "mdi-music-off" }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip
                    :text="
                      ttsEnabled
                        ? narrationEnabled
                          ? 'Disable voice narration'
                          : 'Enable voice narration'
                        : 'TTS disabled by server'
                    "
                    location="bottom"
                  >
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="ttsEnabled && (narrationEnabled = !narrationEnabled)"
                        :color="narrationEnabled && ttsEnabled ? 'success' : 'grey'"
                        :variant="narrationEnabled && ttsEnabled ? 'flat' : 'outlined'"
                        :disabled="!ttsEnabled"
                        icon
                        size="small"
                      >
                        <v-icon>{{ narrationEnabled ? "mdi-account-voice" : "mdi-account-voice-off" }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                </div>
              </v-col>
            </v-row>
          </v-card-title>

          <div class="game-output-wrapper">
            <div class="game-output" ref="gameOutput">
              <div v-for="(message, index) in messages" :key="index" class="game-message" :class="message.type">
                <div v-if="message.image || message.imageLoading" class="scene-image-container">
                  <img
                    v-if="message.image"
                    :src="'data:image/png;base64,' + message.image"
                    alt="Scene visualization"
                    class="scene-image"
                  />
                  <div v-else-if="message.imageLoading" class="image-loading">
                    <v-progress-circular indeterminate color="primary" size="40" width="3"></v-progress-circular>
                  </div>
                </div>
                <div class="message-text">
                  {{ message.text }}
                  <span v-if="message.streaming" class="streaming-cursor">▊</span>
                  <div v-if="message.scene && message.scene.exits && message.scene.exits.length > 0" class="exits-info">
                    <strong>Exits:</strong>
                    <span v-for="(exit, index) in message.scene.exits" :key="index">
                      {{ exit.direction }}
                      <span v-if="exit.description">({{ exit.description }})</span>
                      <span v-if="Number(index) < message.scene.exits.length - 1">,</span>
                    </span>
                  </div>
                </div>
              </div>
              <div v-if="loading" class="loading-indicator">
                <v-progress-circular
                  indeterminate
                  color="primary"
                  size="20"
                  width="2"
                  class="mr-2"
                ></v-progress-circular>
                <span>Thinking...</span>
              </div>
            </div>
          </div>

          <div class="game-input-wrapper">
            <v-text-field
              v-model="userInput"
              @keyup.enter="sendCommand"
              placeholder="Enter your command..."
              variant="outlined"
              density="compact"
              hide-details
              class="game-input"
              autofocus
            >
              <template v-slot:append-inner>
                <v-tooltip text="Hold to capture speech" location="top">
                  <template v-slot:activator="{ props }">
                    <button
                      v-bind="props"
                      @mousedown.prevent="startRecording"
                      @mouseup.prevent="stopRecording"
                      @mouseleave.prevent="stopRecording"
                      @touchstart.prevent="startRecording"
                      @touchend.prevent="stopRecording"
                      @touchcancel.prevent="stopRecording"
                      :disabled="loading || !sttEnabled"
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
                      :disabled="!userInput.trim() || loading"
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
    <v-snackbar v-model="errorSnackbar" color="error" :timeout="6000" location="top">
      {{ errorMessage }}
      <template v-slot:actions>
        <v-btn variant="text" @click="errorSnackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import { defineComponent, ref, nextTick, onMounted, onUnmounted, watch, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import AdventureSetup from "@/components/AdventureSetup.vue";
import GameSelection from "@/components/GameSelection.vue";
import { useWebSocketGame } from "@/composables/useWebSocketGame";
import { useAudioPlayback } from "@/composables/useAudioPlayback";
import type { AdventureConfig, AdventureOutline, StyleChoice, ThemeChoice } from "@/types/api";
import type { SceneData } from "@/types/WebSocketProtocol";

// A message as the view shows it
type DisplayMessage = {
  text: string;
  type: "user" | "game";
  image?: string;
  imageLoading?: boolean;
  scene?: SceneData;
  streaming?: boolean;
};

export default defineComponent({
  name: "GameView",
  components: {
    AdventureSetup,
    GameSelection,
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
      messages: wsMessages,
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
    const gameOutput = ref<HTMLElement>();
    // Waiting for the response to a typed or spoken command
    const loading = computed(() => isStreaming.value || awaitingResponse.value);
    const recording = ref(false);
    const mediaRecorder = ref<MediaRecorder | null>(null);
    const audioChunks = ref<Blob[]>([]);
    const gameStarted = ref(false);
    const setupStarted = ref(false);
    const selectionStarted = ref(false);
    const adventureTheme = ref<ThemeChoice | null>(null);
    const artStyle = ref<StyleChoice | null>(null);
    const adventureOutline = ref<AdventureOutline | null>(null);
    const adventureTitle = ref<string>("Generative Adventuring");
    const imageGenerationEnabled = ref(true);
    const speechInputRequested = ref(true);
    // The microphone stream while recording, released when recording stops or the view unmounts
    let micStream: MediaStream | null = null;
    // Whether the push-to-talk button is held, and whether the microphone is being requested
    let micPressed = false;
    let acquiringMic = false;
    let unmounted = false;
    // The game this view last asked to load (gameLoaded may not have arrived yet)
    let requestedGameId: string | null = null;

    // Map WebSocket messages to the expected format for the UI
    const messages = computed(() => {
      return wsMessages.value.map((msg): DisplayMessage => {
        if (msg.isUser) {
          return {
            text: `> ${msg.text}`,
            type: "user",
            streaming: false,
          };
        } else {
          const gameMsg: DisplayMessage = {
            text: msg.text,
            type: "game",
            scene: msg.scene,
            imageLoading: msg.imageLoading,
            streaming: msg.isStreaming || false,
          };

          // Add image if available
          if (msg.image) {
            gameMsg.image = msg.image;
          }

          return gameMsg;
        }
      });
    });

    // Use the log function from the composable
    const log = wsLog;

    const scrollToBottom = async () => {
      await nextTick();
      if (gameOutput.value) {
        gameOutput.value.scrollTop = gameOutput.value.scrollHeight;
      }
    };

    const beginSelection = () => {
      selectionStarted.value = true;
    };

    const startNewGame = () => {
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
      adventureTitle.value = "Generative Adventuring";
    };

    const goBackToSelection = () => {
      // Reset from AdventureSetup to GameSelection
      setupStarted.value = false;
      selectionStarted.value = true;
    };

    const onAdventureReady = (config: AdventureConfig) => {
      adventureTheme.value = config.theme;
      artStyle.value = config.style;
      adventureOutline.value = config.outline;
      imageGenerationEnabled.value = config.sessionFeatures.image;
      backgroundMusicEnabled.value = config.sessionFeatures.music;
      narrationEnabled.value = config.sessionFeatures.tts;
      speechInputRequested.value = config.sessionFeatures.stt;
      // Set the adventure title if available
      if (config.outline && config.outline.title) {
        adventureTitle.value = config.outline.title;
      }
      gameStarted.value = true;
      setupStarted.value = false;
      nextTick(() => {
        startGame();
      });
    };

    const loadGame = async (gameId: string) => {
      log(`Loading game: ${gameId}`);
      requestedGameId = gameId;
      stopBackgroundMusic();
      // The server answers with gameLoaded, or an error that sets loadFailed
      await wsLoadGame(gameId);
      gameStarted.value = true;
      setupStarted.value = false;
      selectionStarted.value = false;
      if (route.params.gameId !== gameId) router.push(`/game/${gameId}`);
      await scrollToBottom();
    };

    // A game that cannot be loaded (deleted, or an invalid link) returns to the start screen
    watch(loadFailed, (failure) => {
      if (!failure) return;
      log("Error loading game:", failure);
      gameStarted.value = false;
      setupStarted.value = false;
      selectionStarted.value = false;
      router.push("/");
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
          "Starting game with theme:",
          adventureTheme.value,
          "and style:",
          artStyle.value,
          "and outline:",
          adventureOutline.value
        );

        // Clean up any existing audio when starting a new game
        stopBackgroundMusic();

        // Start game via WebSocket
        await wsStartNewGame(
          adventureTheme.value?.name,
          artStyle.value?.id,
          imageGenerationEnabled.value,
          adventureOutline.value,
          {
            tts: narrationEnabled.value,
            music: backgroundMusicEnabled.value,
            stt: speechInputRequested.value,
          }
        );

        // Set the adventure title from the outline if available
        if (adventureOutline.value && adventureOutline.value.title) {
          adventureTitle.value = adventureOutline.value.title;
        }
      } catch (error) {
        log("Error starting game:", error);
        showError("Failed to start game. Please try again.");
        // Go back to setup screen
        gameStarted.value = false;
        setupStarted.value = true;
      }
    };

    const sendCommand = async () => {
      const command = userInput.value.trim();
      // One command at a time, so responses cannot arrive out of order
      if (!command || loading.value) return;

      // Clear input
      userInput.value = "";

      try {
        await scrollToBottom();
        // Send command via WebSocket (streaming is default)
        await wsSendCommand(command, true, imageGenerationEnabled.value);
        await scrollToBottom();
      } catch (error) {
        log("Error sending command:", error);
      }
    };

    const startRecording = async () => {
      log("Start recording called");
      micPressed = true;
      if (recording.value || acquiringMic) {
        log("Already recording, ignoring");
        return;
      }

      try {
        acquiringMic = true;
        let stream: MediaStream;
        try {
          stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        } finally {
          acquiringMic = false;
        }
        micStream = stream;
        // The button was released (or the view closed) while the browser asked for permission
        if (!micPressed || unmounted) {
          log("Button released before the microphone was ready, not recording");
          releaseMicrophone();
          return;
        }

        // Try to use a format that's more compatible with Whisper
        let options = {};
        // Try different formats in order of preference
        const formats = ["audio/wav", "audio/ogg;codecs=opus", "audio/webm;codecs=opus", "audio/webm"];

        for (const format of formats) {
          if (MediaRecorder.isTypeSupported(format)) {
            options = { mimeType: format };
            log("Using audio format:", format);
            break;
          }
        }

        const recorder = new MediaRecorder(stream, options);
        mediaRecorder.value = recorder;
        audioChunks.value = [];

        recorder.ondataavailable = (event) => {
          if (event.data.size > 0) {
            audioChunks.value.push(event.data);
          }
        };

        recorder.onstop = async () => {
          // Release this recording's stream (a newer recording may have its own by now)
          stream.getTracks().forEach((track) => track.stop());
          if (micStream === stream) micStream = null;
          // Stopped because the view closed: there is no game to send the command to
          if (unmounted) return;
          const mimeType = recorder.mimeType || "audio/webm";
          const audioBlob = new Blob(audioChunks.value, { type: mimeType });
          log("Recording stopped, mime type:", mimeType, "size:", audioBlob.size);
          await sendAudioCommand(audioBlob);
        };

        recorder.start();
        recording.value = true;
        log("Recording started successfully");
      } catch (error) {
        log("Error accessing microphone:", error);
        releaseMicrophone();
        addLocalMessage("Error: Could not access microphone. Please check permissions.");
      }
    };

    const releaseMicrophone = () => {
      micStream?.getTracks().forEach((track) => track.stop());
      micStream = null;
    };

    const stopRecording = () => {
      log("Stop recording called", "recording:", recording.value);
      micPressed = false;
      if (mediaRecorder.value && recording.value) {
        mediaRecorder.value.stop();
        recording.value = false;
        log("Recording stopped");
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
        await scrollToBottom();

        // Convert blob to base64
        const base64Audio = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => {
            const base64 = reader.result as string;
            // Remove the data:audio/webm;base64, prefix
            resolve(base64.split(",")[1]);
          };
          reader.onerror = () => reject(reader.error ?? new Error("Could not read the recording"));
          reader.readAsDataURL(audioBlob);
        });

        // Send via WebSocket - the transcription and response will come via WebSocket events
        await wsSendAudioCommand(base64Audio, imageGenerationEnabled.value);
      } catch (error) {
        addLocalMessage("Error processing audio. Please try again.");
        log("Error sending audio:", error);
      } finally {
        await scrollToBottom();
      }
    };

    onMounted(async () => {
      // Set up auto-scroll callback for streaming
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

    onUnmounted(() => {
      log("Component unmounting, cleaning up the microphone");
      unmounted = true;
      if (mediaRecorder.value && recording.value) mediaRecorder.value.stop();
      releaseMicrophone();
    });

    return {
      messages,
      userInput,
      gameOutput,
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
      backToSelection,
      goBackToSelection,
      onAdventureReady,
      backgroundMusicEnabled,
      loadGame,
      adventureTitle,
      imageGenerationEnabled,
      // Feature flags from server
      ttsEnabled,
      sttEnabled,
      musicEnabled,
      imageEnabled,
      errorSnackbar,
      errorMessage,
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

/* Streaming cursor animation */
.streaming-cursor {
  display: inline-block;
  animation: blink 1s infinite;
  color: #4caf50;
  font-weight: bold;
}

@keyframes blink {
  0%,
  50% {
    opacity: 1;
  }
  51%,
  100% {
    opacity: 0;
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

.game-output-wrapper {
  flex: 1;
  overflow: hidden;
  background-color: #1e1e1e;
  border-radius: 4px;
  margin-bottom: 1rem;
}

.game-output {
  height: 100%;
  overflow-y: auto;
  font-family: "Courier New", Courier, monospace;
  padding: 1rem;
}

.game-input-wrapper {
  flex-shrink: 0;
  background-color: #121212;
  padding: 0.5rem 0;
}

.game-message {
  margin-bottom: 0.5rem;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
  gap: 1rem;
}

.game-message.user {
  color: #64b5f6;
}

.game-message.game {
  color: #81c784;
}

.game-message.system {
  color: #ffb74d;
  font-style: italic;
}

.exits-info {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #4caf50;
  font-size: 0.95em;
}

.game-input {
  font-family: "Courier New", Courier, monospace;
  background-color: #1e1e1e !important;
}

/* Custom scrollbar for game output */
.game-output::-webkit-scrollbar {
  width: 8px;
}

.game-output::-webkit-scrollbar-track {
  background: #0d0d0d;
}

.game-output::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.game-output::-webkit-scrollbar-thumb:hover {
  background: #777;
}

.loading-indicator {
  display: flex;
  align-items: center;
  color: #9e9e9e;
  font-style: italic;
  margin-top: 0.5rem;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
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

.audio-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.scene-image-container {
  flex-shrink: 0;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  width: 256px;
  height: 256px;
  order: -1; /* Places image on the left */
}

.scene-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  border-radius: 8px;
}

.message-text {
  flex: 1;
}

.image-loading {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(255, 255, 255, 0.05);
}
</style>
