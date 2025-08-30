<template>
  <v-container fluid class="game-container pa-0">
    <!-- Intro Screen -->
    <div v-if="!gameStarted && !setupStarted && !selectionStarted" class="intro-screen">
      <div class="intro-content" v-if="!loading">
        <img src="/SZork_intro.webp" alt="Welcome to Szork" class="intro-image" />
        <v-btn 
          size="x-large" 
          class="begin-button begin-adventure-btn"
          @click="beginSelection"
        >
          Begin Your Adventure
          <v-icon end>mdi-chevron-right</v-icon>
        </v-btn>
      </div>
      <div class="intro-content" v-else>
        <v-progress-circular
          indeterminate
          color="primary"
          size="64"
          width="4"
        />
        <div class="text-h6 mt-4">Loading saved game...</div>
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
              <v-col cols="auto">
                SZork - {{ adventureTitle }}
              </v-col>
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
                  <v-tooltip :text="imageGenerationEnabled ? 'Disable image generation' : 'Enable image generation'" location="bottom">
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="imageGenerationEnabled = !imageGenerationEnabled"
                        :color="imageGenerationEnabled ? 'info' : 'grey'"
                        :variant="imageGenerationEnabled ? 'flat' : 'outlined'"
                        icon
                        size="small"
                        class="mr-2"
                      >
                        <v-icon>{{ imageGenerationEnabled ? 'mdi-image' : 'mdi-image-off' }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip :text="backgroundMusicEnabled ? 'Disable background music' : 'Enable background music'" location="bottom">
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="backgroundMusicEnabled = !backgroundMusicEnabled"
                        :color="backgroundMusicEnabled ? 'primary' : 'grey'"
                        :variant="backgroundMusicEnabled ? 'flat' : 'outlined'"
                        icon
                        size="small"
                        class="mr-2"
                      >
                        <v-icon>{{ backgroundMusicEnabled ? 'mdi-music' : 'mdi-music-off' }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                  <v-tooltip :text="narrationEnabled ? 'Disable voice narration' : 'Enable voice narration'" location="bottom">
                    <template v-slot:activator="{ props }">
                      <v-btn
                        v-bind="props"
                        @click="narrationEnabled = !narrationEnabled"
                        :color="narrationEnabled ? 'success' : 'grey'"
                        :variant="narrationEnabled ? 'flat' : 'outlined'"
                        icon
                        size="small"
                      >
                        <v-icon>{{ narrationEnabled ? 'mdi-account-voice' : 'mdi-account-voice-off' }}</v-icon>
                      </v-btn>
                    </template>
                  </v-tooltip>
                </div>
              </v-col>
            </v-row>
          </v-card-title>
          
          <div class="game-output-wrapper">
            <div class="game-output" ref="gameOutput">
              <div
                v-for="(message, index) in messages"
                :key="index"
                class="game-message"
                :class="message.type"
              >
                <div v-if="message.image || message.imageLoading" class="scene-image-container">
                  <img 
                    v-if="message.image"
                    :src="'data:image/png;base64,' + message.image" 
                    alt="Scene visualization"
                    class="scene-image"
                  />
                  <div v-else-if="message.imageLoading" class="image-loading">
                    <v-progress-circular
                      indeterminate
                      color="primary"
                      size="40"
                      width="3"
                    ></v-progress-circular>
                  </div>
                </div>
                <div class="message-text">
                  {{ message.text }}
                  <span v-if="message.streaming" class="streaming-cursor">▊</span>
                  <div v-if="message.scene && message.scene.exits && message.scene.exits.length > 0" class="exits-info">
                    <strong>Exits:</strong> 
                    <span v-for="(exit, index) in message.scene.exits" :key="index">
                      {{ exit.direction }}<span v-if="exit.description"> ({{ exit.description }})</span><span v-if="index < message.scene.exits.length - 1">, </span>
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
              @keyup.enter="sendCommandMain"
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
                      :disabled="loading"
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
                      @click="sendCommandMain"
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
  </v-container>
</template>

<script lang="ts">
import { defineComponent, ref, nextTick, onMounted, onUnmounted, watch, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import AdventureSetup from "@/components/AdventureSetup.vue";
import GameSelection from "@/components/GameSelection.vue";
import { useWebSocketGame } from "@/composables/useWebSocketGame";

// Types are imported from the composable
type GameMessage = {
  text: string;
  type: "user" | "game" | "system";
  image?: string | null;
  messageIndex?: number;
  hasImage?: boolean;
  imageLoading?: boolean;
  scene?: any;
  streaming?: boolean;
  isUser?: boolean;
  timestamp?: Date;
  imageUrl?: string;
  backgroundMusic?: string;
  musicMood?: string;
  isStreaming?: boolean;
};

export default defineComponent({
  name: "GameView",
  components: {
    AdventureSetup,
    GameSelection
  },
  props: {
    gameId: {
      type: String,
      default: null
    }
  },
  setup(props) {
    const router = useRouter();
    const route = useRoute();
    
    // Use the WebSocket composable
    const {
      sessionId,
      gameId: currentGameId,
      messages: wsMessages,
      isConnected,
      isStreaming,
      savedGames,
      connect,
      disconnect,
      startNewGame: wsStartNewGame,
      loadGame: wsLoadGame,
      sendCommand: wsSendCommand,
      sendAudioCommand: wsSendAudioCommand,
      getSavedGames,
      setScrollCallback,
      setPlayBackgroundMusicCallback,
      setPlayAudioNarrationCallback,
      log: wsLog
    } = useWebSocketGame();
    
    // Local UI state
    const userInput = ref("");
    const gameOutput = ref<HTMLElement>();
    const loading = computed(() => isStreaming.value);
    const recording = ref(false);
    const mediaRecorder = ref<MediaRecorder | null>(null);
    const audioChunks = ref<Blob[]>([]);
    const narrationEnabled = ref(true);
    const narrationVolume = ref(0.8);
    const gameStarted = ref(false);
    const setupStarted = ref(false);
    const selectionStarted = ref(false);
    const adventureTheme = ref<any>(null);
    const artStyle = ref<any>(null);
    const adventureOutline = ref<any>(null);
    const adventureTitle = ref<string>("Generative Adventuring");
    const backgroundMusicEnabled = ref(true);
    const backgroundMusicVolume = ref(0.3);
    const currentBackgroundMusic = ref<HTMLAudioElement | null>(null);
    const currentMusicMood = ref<string | null>(null);
    const currentNarration = ref<HTMLAudioElement | null>(null);
    const imageGenerationEnabled = ref(true);
    
    // Map WebSocket messages to the expected format for the UI
    const messages = computed(() => {
      return wsMessages.value.map((msg): GameMessage => {
        if (msg.isUser) {
          return {
            text: `> ${msg.text}`,
            type: "user",
            streaming: false
          };
        } else {
          const gameMsg: GameMessage = {
            text: msg.text,
            type: "game",
            messageIndex: msg.messageIndex,
            scene: msg.scene,
            hasImage: msg.hasImage,
            imageLoading: msg.imageLoading,
            streaming: msg.isStreaming || false
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
      // Clean up any existing audio
      if (currentBackgroundMusic.value) {
        currentBackgroundMusic.value.pause();
        currentBackgroundMusic.value.src = "";
        currentBackgroundMusic.value = null;
        currentMusicMood.value = null;
      }
      if (currentNarration.value) {
        currentNarration.value.pause();
        currentNarration.value.src = "";
        currentNarration.value = null;
      }
      
      // Reset game state
      gameStarted.value = false;
      setupStarted.value = false;
      selectionStarted.value = true;
      sessionId.value = null;
      currentGameId.value = null;
      messages.value.length = 0; // Clear messages array without reassigning
      adventureTitle.value = "Generative Adventuring";
      
      // Clear the game ID from the URL if present
      if (route.params.gameId) {
        router.push('/');
      }
    };

    const goBackToSelection = () => {
      // Reset from AdventureSetup to GameSelection
      setupStarted.value = false;
      selectionStarted.value = true;
    };

    const beginSetup = () => {
      setupStarted.value = true;
    };
    
    const onAdventureReady = (config: { theme: any, style: any, outline?: any }) => {
      adventureTheme.value = config.theme;
      artStyle.value = config.style;
      adventureOutline.value = config.outline || null;
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
      try {
        log(`Loading game: ${gameId}`);
        
        // Clean up any existing audio when loading a new game
        if (currentBackgroundMusic.value) {
          currentBackgroundMusic.value.pause();
          currentBackgroundMusic.value.src = "";
          currentBackgroundMusic.value = null;
          currentMusicMood.value = null;
        }
        
        // Load game via WebSocket
        await wsLoadGame(gameId);
        
        // WebSocket will handle loading the game
        // The gameLoaded event will update messages and state
        gameStarted.value = true;
        
        // Update URL if not already there
        if (!route.params.gameId || route.params.gameId !== gameId) {
          router.push(`/game/${gameId}`);
        }
        
        // Scroll to bottom after loading
        await nextTick();
        await scrollToBottom();
        
      } catch (error) {
        log("Error loading game:", error);
        // Show error message and redirect to home
        alert(`Unable to load saved game.\n\nThe game may have been deleted or corrupted.\n\nYou will be redirected to the main screen.`);
        // Reset to initial state
        gameStarted.value = false;
        setupStarted.value = false;
        selectionStarted.value = false;
        // Clear the game ID from the URL
        router.push('/');
      }
    };
    
    const startGame = async () => {
      try {
        log("Starting game with theme:", adventureTheme.value, "and style:", artStyle.value, "and outline:", adventureOutline.value);
        
        // Clean up any existing audio when starting a new game
        if (currentBackgroundMusic.value) {
          currentBackgroundMusic.value.pause();
          currentBackgroundMusic.value.src = "";
          currentBackgroundMusic.value = null;
          currentMusicMood.value = null;
        }
        
        // Start game via WebSocket
        const theme = adventureTheme.value?.name || adventureTheme.value;
        const artStyleValue = artStyle.value?.id || artStyle.value;
        await wsStartNewGame(theme, artStyleValue, imageGenerationEnabled.value, adventureOutline.value);
        
        // Set the adventure title from the outline if available
        if (adventureOutline.value && adventureOutline.value.title) {
          adventureTitle.value = adventureOutline.value.title;
        }
        
        // Update URL to include game ID once we have it
        // The gameStarted event will provide the game ID
        
      } catch (error) {
        log("Error starting game:", error);
        alert("Failed to start game. Please try again.");
        // Go back to setup screen
        gameStarted.value = false;
        setupStarted.value = true;
      }
    };

    const sendCommand = async () => {
      const command = userInput.value.trim();
      if (!command) return;

      // Clear input
      userInput.value = "";

      try {
        await scrollToBottom();
        // Send command via WebSocket (streaming is default)
        await wsSendCommand(command, true);
        await scrollToBottom();
      } catch (error) {
        log("Error sending command:", error);
      }
    };
    
    // Streaming is handled by the WebSocket composable automatically
    
    // Just use sendCommand (WebSocket handles streaming)
    const sendCommandMain = async () => {
      await sendCommand();
    };

    const startRecording = async () => {
      log("Start recording called");
      if (recording.value) {
        log("Already recording, ignoring");
        return;
      }
      
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        
        // Try to use a format that's more compatible with Whisper
        let options = {};
        // Try different formats in order of preference
        const formats = [
          'audio/wav',
          'audio/ogg;codecs=opus',
          'audio/webm;codecs=opus',
          'audio/webm'
        ];
        
        for (const format of formats) {
          if (MediaRecorder.isTypeSupported(format)) {
            options = { mimeType: format };
            log('Using audio format:', format);
            break;
          }
        }
        
        mediaRecorder.value = new MediaRecorder(stream, options);
        audioChunks.value = [];

        mediaRecorder.value.ondataavailable = (event) => {
          if (event.data.size > 0) {
            audioChunks.value.push(event.data);
          }
        };

        mediaRecorder.value.onstop = async () => {
          // Try to use a more compatible audio format
          const mimeType = mediaRecorder.value?.mimeType || "audio/webm";
          const audioBlob = new Blob(audioChunks.value, { type: mimeType });
          log("Recording stopped, mime type:", mimeType, "size:", audioBlob.size);
          await sendAudioCommand(audioBlob);
          
          // Stop all tracks
          stream.getTracks().forEach(track => track.stop());
        };

        mediaRecorder.value.start();
        recording.value = true;
        log("Recording started successfully");
      } catch (error) {
        log("Error accessing microphone:", error);
        messages.value.push({
          text: "Error: Could not access microphone. Please check permissions.",
          type: "system",
        });
      }
    };

    const stopRecording = () => {
      log("Stop recording called", "recording:", recording.value);
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
        // Add error message locally
        wsMessages.value.push({
          text: "Please hold the record button to record audio",
          isUser: false,
          timestamp: new Date()
        });
        return;
      }

      try {
        await scrollToBottom();

        // Convert blob to base64
        const reader = new FileReader();
        reader.readAsDataURL(audioBlob);
        const base64Audio = await new Promise<string>((resolve) => {
          reader.onloadend = () => {
            const base64 = reader.result as string;
            // Remove the data:audio/webm;base64, prefix
            resolve(base64.split(',')[1]);
          };
        });

        // Send via WebSocket - the transcription and response will come via WebSocket events
        await wsSendAudioCommand(base64Audio);
      } catch (error) {
        // Add error message locally
        wsMessages.value.push({
          text: "Error processing audio. Please try again.",
          isUser: false,
          timestamp: new Date()
        });
        log("Error sending audio:", error);
      } finally {
        await scrollToBottom();
      }
    };

    // Image and music polling are handled by WebSocket events now
    // These functions are kept as stubs for backward compatibility
    const pollForImage = () => { /* deprecated - handled by WebSocket */ };
    const pollForMusic = () => { /* deprecated - handled by WebSocket */ };

    const playAudioNarration = (audioBase64: string) => {
      log(`[${sessionId.value || 'no-session'}] playAudioNarration called, narration enabled:`, narrationEnabled.value);
      log(`[${sessionId.value || 'no-session'}] Audio base64 length:`, audioBase64.length);
      
      if (!narrationEnabled.value) {
        log(`[${sessionId.value || 'no-session'}] Narration is disabled, skipping playback`);
        return;
      }
      
      try {
        // Stop any current narration
        if (currentNarration.value && !currentNarration.value.paused) {
          currentNarration.value.pause();
          currentNarration.value = null;
        }
        
        // Create audio element
        const audioUrl = `data:audio/mp3;base64,${audioBase64}`;
        log(`[${sessionId.value || 'no-session'}] Creating audio element with URL length:`, audioUrl.length);
        
        const audio = new Audio(audioUrl);
        audio.volume = narrationVolume.value;
        currentNarration.value = audio;
        
        // Add event listeners for debugging
        audio.addEventListener('loadeddata', () => {
          log(`[${sessionId.value || 'no-session'}] Audio loaded successfully, duration:`, audio.duration);
        });
        
        audio.addEventListener('error', (e) => {
          log(`[${sessionId.value || 'no-session'}] Audio error event:`, e);
          log(`[${sessionId.value || 'no-session'}] Audio error details:`, audio.error);
        });
        
        audio.addEventListener('play', () => {
          log(`[${sessionId.value || 'no-session'}] Audio started playing`);
        });
        
        audio.addEventListener('ended', () => {
          log(`[${sessionId.value || 'no-session'}] Audio playback ended`);
          if (currentNarration.value === audio) {
            currentNarration.value = null;
          }
        });
        
        // Play the audio
        log(`[${sessionId.value || 'no-session'}] Attempting to play audio...`);
        audio.play()
          .then(() => {
            log(`[${sessionId.value || 'no-session'}] Audio play() promise resolved successfully`);
          })
          .catch(error => {
            log(`[${sessionId.value || 'no-session'}] Error playing audio narration:`, error);
            log(`[${sessionId.value || 'no-session'}] Error type:`, error.name, "Message:", error.message);
          });
        
        log(`[${sessionId.value || 'no-session'}] Playing audio narration at volume:`, narrationVolume.value);
      } catch (error) {
        log(`[${sessionId.value || 'no-session'}] Error creating audio element:`, error);
        log(`[${sessionId.value || 'no-session'}] Error details:`, error);
      }
    };

    // Music is now delivered via WebSocket - no polling needed

    const playBackgroundMusic = (musicBase64: string, mood: string) => {
      log(`Playing background music, mood: ${mood}, enabled: ${backgroundMusicEnabled.value}`);
      
      if (!backgroundMusicEnabled.value) {
        log("Background music is disabled, skipping");
        return;
      }
      
      try {
        // Clean up any existing music first
        if (currentBackgroundMusic.value) {
          log("Stopping current background music");
          currentBackgroundMusic.value.pause();
          currentBackgroundMusic.value.src = "";
          currentBackgroundMusic.value = null;
        }
        
        // Create new audio element
        const audioUrl = `data:audio/mp3;base64,${musicBase64}`;
        const newMusic = new Audio(audioUrl);
        newMusic.volume = backgroundMusicVolume.value;
        newMusic.loop = true; // Loop background music
        
        // Store reference and mood
        currentBackgroundMusic.value = newMusic;
        currentMusicMood.value = mood;
        
        // Play the music
        newMusic.play()
          .then(() => {
            log("Background music started successfully");
          })
          .catch(error => {
            log("Error playing background music:", error);
            // Clean up on error
            currentBackgroundMusic.value = null;
            currentMusicMood.value = null;
          });
        
      } catch (error) {
        log("Error creating background music element:", error);
        currentBackgroundMusic.value = null;
        currentMusicMood.value = null;
      }
    };

    // Watch for background music volume changes
    watch(backgroundMusicVolume, (newVolume) => {
      if (currentBackgroundMusic.value && !currentBackgroundMusic.value.paused) {
        currentBackgroundMusic.value.volume = newVolume;
      }
    });

    // Watch for narration volume changes
    watch(narrationVolume, (newVolume) => {
      if (currentNarration.value && !currentNarration.value.paused) {
        currentNarration.value.volume = newVolume;
      }
    });

    // Watch for background music enabled changes
    watch(backgroundMusicEnabled, (enabled) => {
      if (!enabled) {
        log("Disabling background music");
        // Simply pause the music
        if (currentBackgroundMusic.value && !currentBackgroundMusic.value.paused) {
          currentBackgroundMusic.value.pause();
          log("Background music paused");
        }
      } else if (enabled) {
        log("Background music re-enabled");
        // Resume current music if it exists
        if (currentBackgroundMusic.value && currentBackgroundMusic.value.paused) {
          log("Resuming paused background music");
          currentBackgroundMusic.value.play()
            .then(() => {
              log("Background music resumed");
            })
            .catch(error => {
              log("Error resuming background music:", error);
            });
        }
      }
    });

    // Watch for narration enabled changes
    watch(narrationEnabled, (enabled) => {
      if (!enabled && currentNarration.value && !currentNarration.value.paused) {
        // Stop current narration immediately
        log("Stopping current narration as narration was disabled");
        currentNarration.value.pause();
        currentNarration.value.currentTime = 0;
        currentNarration.value = null;
      }
    });

    // Game saving is now automatic after each command
    // No manual save function needed

    onMounted(async () => {
      // Set up auto-scroll callback for streaming
      setScrollCallback(() => {
        scrollToBottom();
      });
      
      // Set up audio playback callbacks
      setPlayBackgroundMusicCallback(playBackgroundMusic);
      setPlayAudioNarrationCallback(playAudioNarration);
      
      // Connect to WebSocket server
      try {
        await connect();
        log('WebSocket connected successfully');
      } catch (error) {
        log('Failed to connect to WebSocket:', error);
      }
      
      // Check if there's a game ID in the URL
      const gameIdFromRoute = route.params.gameId as string;
      if (gameIdFromRoute) {
        log(`Found game ID in URL: ${gameIdFromRoute}`);
        loadGame(gameIdFromRoute);
      } else {
        // No game ID, don't automatically start anything
        // The user will click "Begin Your Adventure" to go to selection screen
        log("No game ID in URL, waiting for user to begin");
      }
    });

    onUnmounted(() => {
      log("Component unmounting, cleaning up audio");
      // Clean up background music
      if (currentBackgroundMusic.value) {
        currentBackgroundMusic.value.pause();
        currentBackgroundMusic.value.src = "";
        currentBackgroundMusic.value = null;
      }
      // Clean up narration
      if (currentNarration.value) {
        currentNarration.value.pause();
        currentNarration.value.src = "";
        currentNarration.value = null;
      }
    });

    return {
      messages,
      userInput,
      gameOutput,
      sendCommand,
      sendCommandMain,
      loading,
      recording,
      startRecording,
      stopRecording,
      narrationEnabled,
      narrationVolume,
      gameStarted,
      setupStarted,
      selectionStarted,
      beginSelection,
      startNewGame,
      loadSelectedGame,
      backToSelection,
      goBackToSelection,
      beginSetup,
      onAdventureReady,
      backgroundMusicEnabled,
      backgroundMusicVolume,
      currentMusicMood,
      loadGame,
      currentGameId,
      adventureTitle,
      imageGenerationEnabled,
      isConnected
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
  0%, 100% {
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
  0%, 50% {
    opacity: 1;
  }
  51%, 100% {
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
  color: #4CAF50;
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
  0%, 100% {
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