<template>
  <v-container fluid class="game-container pa-0">
    <IntroScreen v-if="screen === 'intro'" @begin="screen = 'selection'" />

    <GameSelection
      v-else-if="screen === 'selection'"
      @start-new-game="startNewGame"
      @load-game="loadGame"
      @play-adventure="playAdventure"
    />

    <AdventureSetup
      v-else-if="screen === 'setup'"
      :previous="previousSetup"
      @adventure-ready="onAdventureReady"
      @back-to-selection="goBackToSelection"
    />

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

          <ConnectionBanner :state="connectionState" @retry="retryConnection" />

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

          <CommandInput
            v-model="userInput"
            :loading="loading"
            :speech-enabled="sttEnabled"
            :disabled="sessionTakenOver"
            @submit="sendCommand"
            @recorded="sendAudioCommand"
            @microphone-error="addLocalMessage('Error: Could not access microphone. Please check permissions.')"
          />
        </div>
      </v-col>
    </v-row>
    <!-- Another tab or window loaded this game: this one stops playing it until the player takes it back -->
    <v-snackbar :model-value="sessionTakenOver && screen === 'game'" color="warning" :timeout="-1" location="top">
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

<script setup lang="ts">
/**
 * The whole game, on both routes (`/` and `/game/:gameId`): the intro, the game list, the setup of a new
 * adventure, and the game itself.
 */
import { ref, nextTick, onMounted, watch, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import AdventureSetup from "@/components/AdventureSetup.vue";
import CommandInput from "@/components/CommandInput.vue";
import ConnectionBanner from "@/components/ConnectionBanner.vue";
import GameSelection from "@/components/GameSelection.vue";
import GameHeaderControls from "@/components/GameHeaderControls.vue";
import IntroScreen from "@/components/IntroScreen.vue";
import MessageList from "@/components/MessageList.vue";
import { useWebSocketGame } from "@/composables/useWebSocketGame";
import { useAudioPlayback } from "@/composables/useAudioPlayback";
import { blobToBase64 } from "@/composables/useVoiceRecorder";
import type { AdventureConfig, AdventureSummary } from "@/types/api";

/** What the player is told when the game server cannot be reached. */
const UNREACHABLE = "Could not reach the game server. Please try again.";

const props = withDefaults(defineProps<{ gameId?: string | null }>(), { gameId: null });

const router = useRouter();
const route = useRoute();

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
  log,
  errorSnackbar,
  errorMessage,
  showError,
} = useWebSocketGame();
const { narrationEnabled, backgroundMusicEnabled, playNarration, playBackgroundMusic, stopBackgroundMusic, stopAll } =
  useAudioPlayback({ log });

/** Which screen is shown: the intro, the game list, a new adventure's setup, or the game. */
const screen = ref<"intro" | "selection" | "setup" | "game">("intro");
const userInput = ref("");
const messageList = ref<InstanceType<typeof MessageList>>();
// Waiting for the response to a typed or spoken command
const loading = computed(() => isStreaming.value || awaitingResponse.value);
// The setup choices of the game being started, so a start that fails returns to them rather than to a fresh setup
const previousSetup = ref<AdventureConfig | null>(null);
// Shown while a game is opening and its first scene has not arrived
const openingText = ref("Starting your adventure...");
// The title of the adventure being started, shown until the server confirms the game with its own
const plannedTitle = ref<string | null>(null);
const title = computed(() => serverTitle.value ?? plannedTitle.value ?? "Generative Adventuring");
const imageGenerationEnabled = ref(true);
// Whether the player asked for spoken commands (in the last setup)
const speechInputRequested = ref(true);
// The game this view last asked to load (gameLoaded may not have arrived yet)
let requestedGameId: string | null = null;

/** Show the latest message; unless forced, only when the player has not scrolled up to read. */
const scrollToBottom = async (force = false) => {
  // The list may only appear on the next render (e.g. right after a game is loaded)
  await nextTick();
  await messageList.value?.scrollToBottom(force);
};

const startNewGame = () => {
  previousSetup.value = null;
  screen.value = "setup";
};

const goBackToSelection = () => {
  previousSetup.value = null;
  screen.value = "selection";
};

/** Leave the current game for the selection screen. */
const resetGame = () => {
  stopAll();
  screen.value = "selection";
  clearMessages();
  requestedGameId = null;
  plannedTitle.value = null;
};

const backToSelection = () => {
  resetGame();
  if (route.params.gameId) router.push("/");
};

/**
 * Leave a game that could not be started: a designed adventure returns to its setup with the choices
 * and the outline kept, so it can be started again without designing it again; a library one to the list.
 */
const abandonStart = () => {
  const setup = previousSetup.value;
  resetGame();
  if (setup) {
    previousSetup.value = setup;
    screen.value = "setup";
  }
};

/** Start a new game: of a designed adventure (`config`), or of a library one (`adventure`). */
const startGame = async (config: AdventureConfig | null, adventure?: AdventureSummary) => {
  previousSetup.value = config;
  plannedTitle.value = config?.outline?.title ?? adventure?.title ?? null;
  if (config) {
    imageGenerationEnabled.value = config.sessionFeatures.image;
    backgroundMusicEnabled.value = config.sessionFeatures.music;
    narrationEnabled.value = config.sessionFeatures.tts;
    speechInputRequested.value = config.sessionFeatures.stt;
  }
  screen.value = "game";
  await nextTick();
  log(
    `Starting game: theme ${config?.theme.id}, style ${config?.style.id},`,
    adventure ? `library adventure ${adventure.id}` : `outline "${config?.outline?.title}"`
  );
  // Clean up any existing audio when starting a new game
  stopBackgroundMusic();
  openingText.value = "Starting your adventure...";
  try {
    await wsStartNewGame({
      theme: config?.theme,
      artStyle: config?.style.id,
      imageGeneration: imageGenerationEnabled.value,
      outline: config?.outline ?? null,
      features: {
        tts: narrationEnabled.value,
        music: backgroundMusicEnabled.value,
        stt: speechInputRequested.value,
      },
      adventureId: config ? config.adventureId : adventure?.id,
    });
  } catch (error) {
    log("Could not connect to start the game:", error);
    showError(UNREACHABLE);
    abandonStart();
  }
};

const onAdventureReady = (config: AdventureConfig) => startGame(config);

/** Start a game of a ready-made adventure: the server has its outline, theme and art style. */
const playAdventure = (adventure: AdventureSummary) => startGame(null, adventure);

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
  screen.value = "game";
  if (route.params.gameId !== gameId) router.push(`/game/${gameId}`);
  await scrollToBottom(true);
};

// A game that cannot be loaded (deleted, or an invalid link) returns to the selection screen
watch(loadFailed, (failure) => {
  if (!failure) return;
  log("Error loading game:", failure);
  backToSelection();
});

watch(startFailed, (failure) => {
  if (!failure) return;
  log("Error starting game:", failure);
  abandonStart();
});

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

// Another tab now plays this game: stop its music and narration here
watch(sessionTakenOver, (taken) => {
  if (taken) stopAll();
});

// Once a new game has an id, put it in the URL so a refresh returns to it
watch(currentGameId, (id) => {
  if (id && screen.value === "game" && route.params.gameId !== id) router.replace(`/game/${id}`);
});

// Both routes use this view, so browser back/forward changes the gameId prop without
// remounting: load the game it names, or leave the game when it names none
watch(
  () => props.gameId,
  (id) => {
    if (id) {
      if (id !== requestedGameId && id !== currentGameId.value) loadGame(id);
    } else if (screen.value === "game") {
      resetGame();
    }
  }
);

/** Try the connection again after the client gave up reconnecting; the session resumes when it opens. */
const retryConnection = async () => {
  try {
    await connect();
  } catch (error) {
    log("Retrying the connection failed:", error);
    showError(UNREACHABLE);
  }
};

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

const sendAudioCommand = async (audio: Blob) => {
  log("Audio blob size:", audio.size, "bytes");
  if (audio.size === 0) {
    addLocalMessage("Please hold the record button to record audio");
    return;
  }
  try {
    // The transcription and response will come via WebSocket events
    await wsSendAudioCommand(await blobToBase64(audio), imageGenerationEnabled.value, audio.type);
  } catch (error) {
    addLocalMessage("Error sending the recording. Please try again.");
    log("Error sending audio:", error);
  } finally {
    await scrollToBottom(true);
  }
};

onMounted(async () => {
  // Follow new text as it arrives, unless the player has scrolled up to read
  setScrollCallback(() => {
    scrollToBottom();
  });
  setPlayBackgroundMusicCallback(playBackgroundMusic);
  setPlayAudioNarrationCallback(playNarration);

  try {
    await connect();
    log("WebSocket connected successfully");
  } catch (error) {
    // Not fatal here: each request connects again, and says so if it cannot
    log("Failed to connect to WebSocket:", error);
  }

  // A game's link opens the game (unless a navigation while connecting already has); otherwise the intro waits
  if (props.gameId && props.gameId !== requestedGameId) {
    log(`Found game ID in URL: ${props.gameId}`);
    loadGame(props.gameId);
  }
});
</script>

<style scoped>
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
</style>
