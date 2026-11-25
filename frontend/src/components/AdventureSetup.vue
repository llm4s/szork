<template>
  <div class="adventure-setup">
    <div class="setup-content">
      <transition name="fade" mode="out-in">
        <!-- Generating Adventure Screen -->
        <div v-if="currentStep === 'generating'" class="setup-step generating-adventure">
          <h2 class="setup-title">Crafting Your Adventure</h2>

          <div class="generation-container">
            <div class="loading-animation">
              <div class="spinner"></div>
              <div class="pulse-ring"></div>
            </div>

            <div class="generation-status">
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
            <div class="session-flags" v-if="featureFlagsLoaded">
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
import { defineComponent, ref, computed, onMounted, watch } from "vue";
import axios from "axios";
import ThemePicker from "@/components/ThemePicker.vue";
import StylePicker from "@/components/StylePicker.vue";
import { ART_STYLES, CUSTOM_THEME_ID, PREDEFINED_THEMES } from "@/data/adventureThemes";
import type {
  AdventureConfig,
  AdventureOutline,
  FeatureFlag,
  FeatureFlags,
  StyleChoice,
  ThemeChoice,
} from "@/types/api";

// The shortest custom theme description worth validating
const MIN_CUSTOM_THEME_LENGTH = 10;

export default defineComponent({
  name: "AdventureSetup",
  components: { ThemePicker, StylePicker },
  emits: {
    "adventure-ready": (config: AdventureConfig) => !!config,
    "back-to-selection": () => true,
  },
  setup(_, { emit }) {
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
    const serverAvailable = ref(true);
    const checkingServer = ref(false);
    const featureFlagsLoaded = ref(false);
    const featureFlags = ref<FeatureFlags>({
      llm: { provider: "", available: true },
      image: { enabled: true, provider: "", available: true },
      music: { enabled: true, available: true },
      tts: { enabled: true, available: true },
      stt: { enabled: true, available: true },
    });
    const enableImages = ref(true);
    const enableMusic = ref(true);
    const enableTTS = ref(true);
    const enableSTT = ref(true);
    // Whether the designed adventure is added to the adventure library, to be played again
    const keepAdventure = ref(false);

    const predefinedThemes = PREDEFINED_THEMES;
    const artStyles = ART_STYLES;

    const canProceedFromTheme = computed(() => {
      if (selectedTheme.value === CUSTOM_THEME_ID) {
        return (
          customThemeInput.value.trim().length >= MIN_CUSTOM_THEME_LENGTH &&
          !customThemeError.value &&
          !validatingCustomTheme.value
        );
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

    const validateCustomTheme = async () => {
      const theme = customThemeInput.value;
      if (theme.trim().length < MIN_CUSTOM_THEME_LENGTH) {
        customThemeError.value = "Please provide a more detailed description";
        return;
      }

      validatingCustomTheme.value = true;
      customThemeError.value = "";

      try {
        const response = await axios.post("/api/game/validate-theme", { theme });
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

    const proceedToStyle = () => {
      if (selectedTheme.value === CUSTOM_THEME_ID && !validatedCustomTheme.value) {
        validateCustomTheme().then(() => {
          if (validatedCustomTheme.value) {
            currentStep.value = "style";
          }
        });
      } else {
        currentStep.value = "style";
      }
    };

    const goBackToTheme = () => {
      currentStep.value = "theme";
    };

    const backToSelection = () => {
      emit("back-to-selection");
    };

    const generateAdventureOutline = async (
      themeData: ThemeChoice,
      styleData: StyleChoice
    ): Promise<AdventureOutline | null> => {
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

        const response = await axios.post("/api/game/generate-adventure", {
          theme: themeData,
          artStyle: styleData,
        });

        clearInterval(statusInterval);

        if (response.data.status === "success") {
          adventureOutline.value = response.data.outline;
          generationStatus.value = "Adventure ready! Starting your journey...";
          generationProgress.value = 100;

          // Wait a moment to show completion
          await new Promise((resolve) => setTimeout(resolve, 1500));

          return response.data.outline as AdventureOutline;
        } else {
          throw new Error(response.data.message || "Failed to generate adventure");
        }
      } catch (error) {
        console.error("Error generating adventure:", error);
        generationStatus.value = "Using default adventure...";
        generationProgress.value = 100;
        await new Promise((resolve) => setTimeout(resolve, 1000));
        return null; // Will proceed without outline
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
        const response = await axios.post("/api/adventures", { outline, theme: themeData, artStyle: styleData });
        if (response.data.status === "success") return response.data.id as string;
        console.warn("The adventure was not kept:", response.data.message);
      } catch (error) {
        console.warn("The adventure was not kept:", error);
      }
      return undefined;
    };

    // Fetch the server's feature flags on mount; an answer also shows the server is available
    onMounted(async () => {
      checkingServer.value = true;
      try {
        const response = await axios.get("/api/feature-flags", { timeout: 3000 });
        featureFlags.value = response.data;
        featureFlagsLoaded.value = true;
        serverAvailable.value = true;
        // A feature the server cannot provide starts switched off
        const offered = (flag: FeatureFlag) => flag.enabled && flag.available;
        enableImages.value = offered(featureFlags.value.image);
        enableMusic.value = offered(featureFlags.value.music);
        enableTTS.value = offered(featureFlags.value.tts);
        enableSTT.value = offered(featureFlags.value.stt);
      } catch (error) {
        console.warn("[AdventureSetup] Server is not available:", error);
        serverAvailable.value = false;
      } finally {
        checkingServer.value = false;
      }
    });

    const startAdventure = async () => {
      if (!selectedTheme.value || !selectedStyle.value) return;

      // Check server availability before starting
      if (!serverAvailable.value) {
        console.error("[AdventureSetup] Cannot start adventure - server not available");
        return;
      }

      // Get the theme and style details
      let themeData: ThemeChoice;
      if (selectedTheme.value === CUSTOM_THEME_ID) {
        themeData = {
          id: CUSTOM_THEME_ID,
          name: "Custom Adventure",
          prompt: validatedCustomTheme.value || customThemeInput.value,
        };
      } else {
        const theme = predefinedThemes.find((t) => t.id === selectedTheme.value);
        if (!theme) return;
        themeData = { id: theme.id, name: theme.name, prompt: theme.prompt };
      }
      const style = artStyles.find((s) => s.id === selectedStyle.value);
      if (!style) return;
      const styleData: StyleChoice = { id: style.id, name: style.name };

      startingGame.value = true;
      currentStep.value = "generating";
      generationProgress.value = 10;

      // Generate the adventure outline
      const outline = await generateAdventureOutline(themeData, styleData);
      // A kept adventure is played from the library, like any ready-made one
      const adventureId =
        outline && keepAdventure.value ? await keepInLibrary(outline, themeData, styleData) : undefined;

      // Emit the ready event with the outline and session feature choices
      emit("adventure-ready", {
        theme: themeData,
        style: styleData,
        outline: outline,
        adventureId,
        sessionFeatures: {
          image: enableImages.value && featureFlags.value.image.enabled && featureFlags.value.image.available,
          music: enableMusic.value && featureFlags.value.music.enabled && featureFlags.value.music.available,
          tts: enableTTS.value && featureFlags.value.tts.enabled && featureFlags.value.tts.available,
          stt: enableSTT.value && featureFlags.value.stt.enabled && featureFlags.value.stt.available,
        },
      });
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
      backToSelection,
      startAdventure,
      adventureOutline,
      generationStatus,
      generationProgress,
      serverAvailable,
      checkingServer,
      featureFlagsLoaded,
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
