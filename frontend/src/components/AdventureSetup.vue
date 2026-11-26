<template>
  <div class="adventure-setup">
    <div class="setup-content">
      <transition name="fade" mode="out-in">
        <!-- Generating Adventure Screen -->
        <div v-if="currentStep === 'generating'" class="setup-step generating-adventure">
          <h2 class="setup-title">Crafting Your Adventure</h2>

          <div class="generation-container">
            <div v-if="!generationError" class="loading-animation">
              <div class="spinner"></div>
              <div class="pulse-ring"></div>
            </div>

            <div v-if="generationError" class="generation-failed" role="alert">
              <p class="failure-text">The adventure could not be designed: {{ generationError }}</p>
              <div class="failure-actions">
                <v-btn variant="outlined" class="retry-generation" @click="startAdventure">
                  <v-icon start>mdi-refresh</v-icon>
                  Try again
                </v-btn>
                <v-btn variant="text" class="play-without-outline" @click="playWithoutOutline">
                  Play without a designed adventure
                </v-btn>
                <v-btn variant="text" @click="goBackToStyle">Back</v-btn>
              </div>
            </div>
            <div v-else class="generation-status">
              <p class="status-text">{{ generationStatus }}</p>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: generationProgress + '%' }"></div>
              </div>
            </div>

            <div v-if="adventureOutline" class="adventure-preview">
              <h3>{{ adventureOutline.title }}</h3>
              <p class="quest-preview">{{ adventureOutline.mainQuest }}</p>
            </div>
          </div>
        </div>

        <!-- Theme Selection -->
        <div v-else-if="currentStep === 'theme'" class="setup-step">
          <div class="setup-header">
            <h2 class="setup-title">Choose Your Adventure Theme</h2>
          </div>

          <div class="setup-scrollable">
            <ThemePicker
              :themes="predefinedThemes"
              :selected="selectedTheme"
              v-model:custom-theme="customThemeInput"
              :custom-theme-error="customThemeError"
              :validating="validatingCustomTheme"
              @select="selectTheme"
              @validate="validateCustomTheme"
            />
          </div>

          <div class="action-buttons-sticky">
            <v-btn variant="outlined" size="large" @click="backToSelection" class="mr-2">
              <v-icon start>mdi-arrow-left</v-icon>
              Back
            </v-btn>
            <v-btn size="large" :disabled="!canProceedFromTheme" @click="proceedToStyle" class="continue-btn">
              Continue to Art Style
              <v-icon end>mdi-chevron-right</v-icon>
            </v-btn>
          </div>
        </div>

        <!-- Art Style Selection -->
        <div v-else-if="currentStep === 'style'" class="setup-step">
          <div class="setup-header">
            <h2 class="setup-title">Choose Your Visual Style</h2>
          </div>

          <div class="setup-scrollable">
            <StylePicker :styles="artStyles" :selected="selectedStyle" @select="selectStyle" />
          </div>

          <div class="action-buttons-sticky">
            <v-btn variant="outlined" size="large" @click="goBackToTheme" class="mr-2">Back</v-btn>
            <!-- Session feature toggles -->
            <div class="session-flags" v-if="featureFlags">
              <v-switch
                density="compact"
                hide-details
                class="mr-2 keep-adventure"
                v-model="keepAdventure"
                label="Keep adventure"
                title="Add this adventure to the library, to play again; its pictures are then made once for every game of it"
              />
              <v-switch
                density="compact"
                hide-details
                class="mr-2"
                v-model="enableImages"
                :disabled="!featureFlags.image.enabled || !featureFlags.image.available"
                :label="`Images ${featureFlags.image.enabled ? (featureFlags.image.available ? '' : '(unavailable)') : '(disabled)'}`"
              />
              <v-switch
                density="compact"
                hide-details
                class="mr-2"
                v-model="enableMusic"
                :disabled="!featureFlags.music.enabled || !featureFlags.music.available"
                :label="`Music ${featureFlags.music.enabled ? (featureFlags.music.available ? '' : '(unavailable)') : '(disabled)'}`"
              />
              <v-switch
                density="compact"
                hide-details
                class="mr-2"
                v-model="enableTTS"
                :disabled="!featureFlags.tts.enabled || !featureFlags.tts.available"
                :label="`TTS ${featureFlags.tts.enabled ? (featureFlags.tts.available ? '' : '(unavailable)') : '(disabled)'}`"
              />
              <v-switch
                density="compact"
                hide-details
                class="mr-2"
                v-model="enableSTT"
                :disabled="!featureFlags.stt.enabled || !featureFlags.stt.available"
                :label="`STT ${featureFlags.stt.enabled ? (featureFlags.stt.available ? '' : '(unavailable)') : '(disabled)'}`"
              />
            </div>
            <v-tooltip
              :text="!serverAvailable ? 'Server not available - please ensure the game server is running' : ''"
              :disabled="serverAvailable"
              location="top"
            >
              <template v-slot:activator="{ props }">
                <v-btn
                  v-bind="props"
                  size="large"
                  :disabled="!selectedStyle || !serverAvailable || checkingServer"
                  @click="startAdventure"
                  :loading="startingGame || checkingServer"
                  class="begin-adventure-btn"
                  :color="!serverAvailable ? 'error' : 'primary'"
                >
                  <span v-if="!serverAvailable">Server Unavailable</span>
                  <span v-else-if="checkingServer">Checking Server...</span>
                  <span v-else>Begin Adventure</span>
                  <v-icon v-if="serverAvailable && !checkingServer" end>mdi-chevron-right</v-icon>
                  <v-icon v-else-if="!serverAvailable" end>mdi-alert-circle</v-icon>
                </v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, computed, onMounted, watch, type PropType } from "vue";
import axios from "axios";
import ThemePicker from "@/components/ThemePicker.vue";
import StylePicker from "@/components/StylePicker.vue";
import { useFeatureFlags } from "@/composables/useFeatureFlags";
import { ART_STYLES, CUSTOM_THEME_ID, PREDEFINED_THEMES } from "@/data/adventureThemes";
import type { AdventureConfig, AdventureOutline, StyleChoice, ThemeChoice } from "@/types/api";

/** The `/api/game/validate-theme` response. */
interface ThemeValidation {
  valid: boolean;
  message?: string;
  enhancedTheme?: string;
}

/** The `/api/game/generate-adventure` response. */
type GenerationResponse = { status: "success"; outline: AdventureOutline } | { status: "error"; message?: string };

/** The `/api/adventures` (keep an adventure) response. */
type KeepResponse = { status: "success"; id: string } | { status: "error"; message?: string };

// The shortest custom theme description worth validating
const MIN_CUSTOM_THEME_LENGTH = 10;

export default defineComponent({
  name: "AdventureSetup",
  components: { ThemePicker, StylePicker },
  props: {
    /** The choices of a game that could not be started: setup resumes from them, keeping their outline. */
    previous: { type: Object as PropType<AdventureConfig | null>, default: null },
  },
  emits: {
    "adventure-ready": (config: AdventureConfig) => !!config,
    "back-to-selection": () => true,
  },
  setup(props, { emit }) {
    const currentStep = ref<"theme" | "style" | "generating">("theme");
    const selectedTheme = ref<string | null>(null);
    const selectedStyle = ref<string | null>(null);
    const customThemeInput = ref("");
    const customThemeError = ref("");
    const validatingCustomTheme = ref(false);
    const startingGame = ref(false);
    const validatedCustomTheme = ref("");
    const adventureOutline = ref<AdventureOutline | null>(null);
    const generationStatus = ref("Creating your unique adventure...");
    const generationProgress = ref(0);
    // Why the adventure could not be designed; the player may try again or play without a design
    const generationError = ref("");
    const {
      flags: featureFlags,
      loading: checkingServer,
      failed: serverUnavailable,
      load: loadFeatureFlags,
      offered,
    } = useFeatureFlags();
    const serverAvailable = computed(() => !serverUnavailable.value);
    const enableImages = ref(true);
    const enableMusic = ref(true);
    const enableTTS = ref(true);
    const enableSTT = ref(true);
    // Whether the designed adventure is added to the adventure library, to be played again
    const keepAdventure = ref(false);
    // The library id of the outline, once kept
    let keptAdventureId: string | undefined;
    // A validation of the custom description that is still running
    let pendingValidation: Promise<void> | null = null;

    // Resume from a game that could not be started, with its outline (so it is not designed again)
    const previous = props.previous;
    if (previous) {
      selectedTheme.value = previous.theme.id;
      if (previous.theme.id === CUSTOM_THEME_ID) {
        customThemeInput.value = previous.theme.prompt;
        validatedCustomTheme.value = previous.theme.prompt;
      }
      selectedStyle.value = previous.style.id;
      adventureOutline.value = previous.outline;
      keepAdventure.value = !!previous.adventureId;
      keptAdventureId = previous.adventureId;
      currentStep.value = "style";
    }

    const predefinedThemes = PREDEFINED_THEMES;
    const artStyles = ART_STYLES;

    const canProceedFromTheme = computed(() => {
      if (selectedTheme.value === CUSTOM_THEME_ID) {
        // Not disabled while validating: the click that ended the editing (and so started the validation)
        // must still land, and proceeding waits for the validation
        return customThemeInput.value.trim().length >= MIN_CUSTOM_THEME_LENGTH && !customThemeError.value;
      }
      return selectedTheme.value !== null;
    });

    const selectTheme = (themeId: string) => {
      selectedTheme.value = themeId;
      if (themeId !== CUSTOM_THEME_ID) {
        customThemeError.value = "";
        validatedCustomTheme.value = "";
      }
    };

    const selectStyle = (styleId: string) => {
      selectedStyle.value = styleId;
    };

    // Editing the description invalidates the last validation (and its error)
    watch(customThemeInput, () => {
      validatedCustomTheme.value = "";
      customThemeError.value = "";
    });

    // Another theme or style needs another design
    watch([selectedTheme, selectedStyle, validatedCustomTheme], () => {
      adventureOutline.value = null;
      keptAdventureId = undefined;
    });

    const validateCustomTheme = (): Promise<void> => {
      const validation = runValidation().finally(() => {
        if (pendingValidation === validation) pendingValidation = null;
      });
      pendingValidation = validation;
      return validation;
    };

    const runValidation = async () => {
      const theme = customThemeInput.value;
      if (theme.trim().length < MIN_CUSTOM_THEME_LENGTH) {
        customThemeError.value = "Please provide a more detailed description";
        return;
      }

      validatingCustomTheme.value = true;
      customThemeError.value = "";

      try {
        const response = await axios.post<ThemeValidation>("/api/game/validate-theme", { theme });
        // The description changed while it was being validated: this result is stale
        if (customThemeInput.value !== theme) return;

        if (response.data.valid) {
          validatedCustomTheme.value = response.data.enhancedTheme || theme;
          customThemeError.value = "";
        } else {
          customThemeError.value = response.data.message || "This theme may not work well for an adventure game";
        }
      } catch (error) {
        console.error("Error validating theme:", error);
        // Allow the theme anyway if validation fails
        if (customThemeInput.value === theme) validatedCustomTheme.value = theme;
      } finally {
        validatingCustomTheme.value = false;
      }
    };

    const proceedToStyle = async () => {
      if (selectedTheme.value === CUSTOM_THEME_ID) {
        // The validation started when the description lost focus may still be running
        if (pendingValidation) await pendingValidation;
        if (!validatedCustomTheme.value && !customThemeError.value) await validateCustomTheme();
        if (!validatedCustomTheme.value) return;
      }
      currentStep.value = "style";
    };

    const goBackToTheme = () => {
      currentStep.value = "theme";
    };

    const goBackToStyle = () => {
      generationError.value = "";
      currentStep.value = "style";
    };

    const backToSelection = () => {
      emit("back-to-selection");
    };

    /** Designs the adventure; throws when it cannot be designed. */
    const generateAdventureOutline = async (
      themeData: ThemeChoice,
      styleData: StyleChoice
    ): Promise<AdventureOutline> => {
      let statusInterval: ReturnType<typeof setInterval> | undefined;
      try {
        // Update status messages during generation
        const statusMessages = [
          "Creating your unique adventure...",
          "Designing exciting locations...",
          "Placing mysterious items...",
          "Bringing characters to life...",
          "Weaving the story together...",
          "Adding the finishing touches...",
        ];

        let messageIndex = 0;
        statusInterval = setInterval(() => {
          messageIndex = (messageIndex + 1) % statusMessages.length;
          generationStatus.value = statusMessages[messageIndex];
          generationProgress.value = Math.min(generationProgress.value + 15, 90);
        }, 2000);

        const response = await axios.post<GenerationResponse>("/api/game/generate-adventure", {
          theme: themeData,
          artStyle: styleData,
        });
        clearInterval(statusInterval);
        if (response.data.status !== "success") throw new Error(response.data.message || "the designer failed");

        adventureOutline.value = response.data.outline;
        generationStatus.value = "Adventure ready! Starting your journey...";
        generationProgress.value = 100;
        // Wait a moment to show completion
        await new Promise((resolve) => setTimeout(resolve, 1500));
        return response.data.outline;
      } finally {
        // Stop the progress messages whether generation succeeded or failed
        if (statusInterval) clearInterval(statusInterval);
      }
    };

    /** Adds the outline to the adventure library; its id there, or undefined if it could not be kept. */
    const keepInLibrary = async (
      outline: AdventureOutline,
      themeData: ThemeChoice,
      styleData: StyleChoice
    ): Promise<string | undefined> => {
      try {
        const response = await axios.post<KeepResponse>("/api/adventures", {
          outline,
          theme: themeData,
          artStyle: styleData,
        });
        if (response.data.status === "success") return response.data.id;
        console.warn("The adventure was not kept:", response.data.message);
      } catch (error) {
        console.warn("The adventure was not kept:", error);
      }
      return undefined;
    };

    // Fetch the server's feature flags on mount; an answer also shows the server is available
    onMounted(async () => {
      await loadFeatureFlags();
      // A feature the server cannot provide starts switched off; a resumed setup keeps the player's choices
      const wanted = previous?.sessionFeatures ?? { image: true, music: true, tts: true, stt: true };
      enableImages.value = wanted.image && offered("image");
      enableMusic.value = wanted.music && offered("music");
      enableTTS.value = wanted.tts && offered("tts");
      enableSTT.value = wanted.stt && offered("stt");
    });

    const themeChoice = (): ThemeChoice | undefined => {
      if (selectedTheme.value === CUSTOM_THEME_ID) {
        return {
          id: CUSTOM_THEME_ID,
          name: "Custom Adventure",
          prompt: validatedCustomTheme.value || customThemeInput.value,
        };
      }
      const theme = predefinedThemes.find((t) => t.id === selectedTheme.value);
      return theme && { id: theme.id, name: theme.name, prompt: theme.prompt };
    };

    const styleChoice = (): StyleChoice | undefined => {
      const style = artStyles.find((s) => s.id === selectedStyle.value);
      return style && { id: style.id, name: style.name };
    };

    /** Hand the choices to the game. */
    const emitReady = (outline: AdventureOutline | null, adventureId?: string) => {
      const theme = themeChoice();
      const style = styleChoice();
      if (!theme || !style) return;
      emit("adventure-ready", {
        theme,
        style,
        outline,
        adventureId,
        sessionFeatures: {
          image: enableImages.value && offered("image"),
          music: enableMusic.value && offered("music"),
          tts: enableTTS.value && offered("tts"),
          stt: enableSTT.value && offered("stt"),
        },
      });
    };

    /** Start the game without a designed adventure: the Dungeon Master makes it up as it goes. */
    const playWithoutOutline = () => {
      generationError.value = "";
      emitReady(null);
    };

    const startAdventure = async () => {
      if (!selectedTheme.value || !selectedStyle.value) return;

      // Check server availability before starting
      if (!serverAvailable.value) {
        console.error("[AdventureSetup] Cannot start adventure - server not available");
        return;
      }

      const themeData = themeChoice();
      const styleData = styleChoice();
      if (!themeData || !styleData) return;

      startingGame.value = true;
      generationError.value = "";
      try {
        // An outline kept from a game that could not be started is not designed again
        let outline = adventureOutline.value;
        if (!outline) {
          currentStep.value = "generating";
          generationProgress.value = 10;
          outline = await generateAdventureOutline(themeData, styleData);
        }
        // A kept adventure is played from the library, like any ready-made one
        if (keepAdventure.value && !keptAdventureId)
          keptAdventureId = await keepInLibrary(outline, themeData, styleData);
        emitReady(outline, keepAdventure.value ? keptAdventureId : undefined);
      } catch (error) {
        console.error("Error generating adventure:", error);
        generationError.value = error instanceof Error ? error.message : String(error);
      } finally {
        startingGame.value = false;
      }
    };

    return {
      currentStep,
      selectedTheme,
      selectedStyle,
      customThemeInput,
      customThemeError,
      validatingCustomTheme,
      startingGame,
      predefinedThemes,
      artStyles,
      canProceedFromTheme,
      selectTheme,
      selectStyle,
      validateCustomTheme,
      proceedToStyle,
      goBackToTheme,
      goBackToStyle,
      backToSelection,
      startAdventure,
      playWithoutOutline,
      adventureOutline,
      generationStatus,
      generationProgress,
      generationError,
      serverAvailable,
      checkingServer,
      featureFlags,
      keepAdventure,
      enableImages,
      enableMusic,
      enableTTS,
      enableSTT,
    };
  },
});
</script>

<style scoped>
.adventure-setup {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  overflow: hidden;
  position: relative;
}

.setup-content {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.setup-step {
  animation: fadeIn 0.5s ease-in;
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}

.setup-header {
  flex-shrink: 0;
  padding: 1.5rem 2rem 1rem;
}

.setup-scrollable {
  flex: 1;
  overflow-y: auto;
  padding: 0 2rem;
  padding-bottom: 120px; /* Space for sticky buttons */
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}

.setup-title {
  text-align: center;
  font-size: 2.5rem;
  margin: 0;
  margin-bottom: 1rem;
  color: #fff;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
}

.action-buttons-sticky {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 1rem;
  padding: 1.5rem;
  background: linear-gradient(to top, rgba(13, 13, 13, 0.95), rgba(13, 13, 13, 0.85));
  backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  z-index: 100;
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.5);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .setup-header {
    padding: 1rem 1rem 0.5rem;
  }

  .setup-scrollable {
    padding: 0 1rem;
    padding-bottom: 100px;
  }

  .setup-title {
    font-size: 1.8rem;
    margin-bottom: 0.75rem;
  }

  .action-buttons-sticky {
    padding: 1rem;
  }

  .continue-btn,
  .begin-adventure-btn {
    font-size: 0.9rem;
    padding: 0 16px !important;
  }
}

.begin-adventure-btn,
.continue-btn {
  background-color: #000000 !important;
  color: #ffffff !important;
  border: 2px solid #ffffff !important;
  border-radius: 8px !important;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.5px;
  padding: 0 24px !important;
  box-shadow:
    inset 2px 2px 4px rgba(255, 255, 255, 0.2),
    inset -2px -2px 4px rgba(0, 0, 0, 0.5),
    0 4px 8px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
}

.begin-adventure-btn:hover:not(:disabled),
.continue-btn:hover:not(:disabled) {
  background-color: #1a1a1a !important;
  border-color: #ffffff !important;
  box-shadow:
    inset 2px 2px 6px rgba(255, 255, 255, 0.3),
    inset -2px -2px 6px rgba(0, 0, 0, 0.6),
    0 6px 12px rgba(0, 0, 0, 0.4);
  transform: translateY(-2px);
}

.begin-adventure-btn:active:not(:disabled),
.continue-btn:active:not(:disabled) {
  box-shadow:
    inset 2px 2px 4px rgba(0, 0, 0, 0.6),
    inset -2px -2px 4px rgba(255, 255, 255, 0.1),
    0 2px 4px rgba(0, 0, 0, 0.2);
  transform: translateY(0);
}

.begin-adventure-btn:disabled,
.continue-btn:disabled {
  opacity: 0.5;
  background-color: #333333 !important;
  border-color: #666666 !important;
}

.generating-adventure {
  text-align: center;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 2rem;
}

.generation-container {
  max-width: 600px;
  margin: 0 auto;
}

.loading-animation {
  position: relative;
  width: 120px;
  height: 120px;
  margin: 2rem auto;
}

.spinner {
  position: absolute;
  width: 100%;
  height: 100%;
  border: 3px solid rgba(255, 255, 255, 0.1);
  border-top: 3px solid #2196f3;
  border-radius: 50%;
  animation: spin 1.5s linear infinite;
}

.pulse-ring {
  position: absolute;
  width: 100%;
  height: 100%;
  border: 2px solid #2196f3;
  border-radius: 50%;
  animation: pulse 2s ease-out infinite;
}

@keyframes spin {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}

@keyframes pulse {
  0% {
    transform: scale(1);
    opacity: 1;
  }
  100% {
    transform: scale(1.5);
    opacity: 0;
  }
}

.generation-status {
  margin: 2rem 0;
}

.status-text {
  font-size: 1.2rem;
  color: #ffffff;
  margin-bottom: 1.5rem;
  animation: fadeInOut 2s ease-in-out infinite;
}

@keyframes fadeInOut {
  0%,
  100% {
    opacity: 0.7;
  }
  50% {
    opacity: 1;
  }
}

.progress-bar {
  width: 100%;
  height: 8px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  overflow: hidden;
  margin: 0 auto;
  max-width: 400px;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #2196f3, #00bcd4);
  border-radius: 4px;
  transition: width 0.5s ease;
  box-shadow: 0 0 10px rgba(33, 150, 243, 0.5);
}

.generation-failed {
  margin: 2rem 0;
  color: #ffffff;
}

.failure-text {
  font-size: 1.1rem;
  margin-bottom: 1.5rem;
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.75rem;
}

.adventure-preview {
  margin-top: 3rem;
  padding: 1.5rem;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  animation: fadeIn 0.5s ease-in;
}

.adventure-preview h3 {
  font-size: 1.5rem;
  color: #2196f3;
  margin-bottom: 0.5rem;
}

.quest-preview {
  font-size: 1rem;
  color: rgba(255, 255, 255, 0.8);
  line-height: 1.5;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Custom Scrollbar Styling */
.setup-scrollable::-webkit-scrollbar {
  width: 8px;
}

.setup-scrollable::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 4px;
}

.setup-scrollable::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
}

.setup-scrollable::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.3);
}
</style>
