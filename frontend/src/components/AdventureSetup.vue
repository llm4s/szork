<template>
  <div class="adventure-setup">
    <div class="setup-content">
      <transition name="fade" mode="out-in">
        <GenerationProgress
          v-if="currentStep === 'generating'"
          :status="designStatus"
          :progress="designProgress"
          :error="generationError"
          :outline="adventureOutline ?? designedOutline"
          @retry="startAdventure"
          @play-without-outline="playWithoutOutline"
          @back="goBackToStyle"
        />

        <!-- Theme Selection -->
        <div v-else-if="currentStep === 'theme'" class="setup-step">
          <div class="setup-header">
            <h2 class="setup-title">Choose Your Adventure Theme</h2>
          </div>

          <div class="setup-scrollable">
            <ThemePicker
              :themes="PREDEFINED_THEMES"
              :selected="selectedTheme"
              v-model:custom-theme="customDescription"
              :custom-theme-error="customThemeError"
              :validating="validatingCustomTheme"
              @select="selectTheme"
              @validate="validateCustomTheme"
            />
          </div>

          <div class="action-buttons-sticky">
            <v-btn variant="outlined" size="large" @click="emit('back-to-selection')" class="mr-2">
              <v-icon start>mdi-arrow-left</v-icon>
              Back
            </v-btn>
            <v-btn
              size="large"
              :disabled="!canProceedFromTheme"
              @click="proceedToStyle"
              class="szork-button continue-btn"
            >
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
            <StylePicker :styles="ART_STYLES" :selected="selectedStyle" @select="selectedStyle = $event" />
          </div>

          <div class="action-buttons-sticky">
            <v-btn variant="outlined" size="large" @click="currentStep = 'theme'" class="mr-2">Back</v-btn>
            <FeatureToggles
              v-if="featureFlags"
              :flags="featureFlags"
              v-model:features="features"
              v-model:keep-adventure="keepAdventure"
            />
            <v-tooltip
              :text="serverUnavailable ? 'Server not available - please ensure the game server is running' : ''"
              :disabled="!serverUnavailable"
              location="top"
            >
              <template v-slot:activator="{ props: tooltip }">
                <v-btn
                  v-bind="tooltip"
                  size="large"
                  :disabled="!selectedStyle || serverUnavailable || checkingServer"
                  @click="startAdventure"
                  :loading="startingGame || checkingServer"
                  class="szork-button begin-adventure-btn"
                  :color="serverUnavailable ? 'error' : 'primary'"
                >
                  <span v-if="serverUnavailable">Server Unavailable</span>
                  <span v-else-if="checkingServer">Checking Server...</span>
                  <span v-else>Begin Adventure</span>
                  <v-icon v-if="!serverUnavailable && !checkingServer" end>mdi-chevron-right</v-icon>
                  <v-icon v-else-if="serverUnavailable" end>mdi-alert-circle</v-icon>
                </v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Setting up a new adventure: a theme (or the player's own description), an art style and the game's
 * features; then the adventure is designed, and handed to the game.
 */
import { computed, onMounted, ref, watch } from "vue";
import ThemePicker from "@/components/ThemePicker.vue";
import StylePicker from "@/components/StylePicker.vue";
import FeatureToggles from "@/components/FeatureToggles.vue";
import GenerationProgress from "@/components/GenerationProgress.vue";
import { useFeatureFlags } from "@/composables/useFeatureFlags";
import { useCustomTheme } from "@/composables/useCustomTheme";
import { useAdventureDesign } from "@/composables/useAdventureDesign";
import { ART_STYLES, CUSTOM_THEME_ID, PREDEFINED_THEMES } from "@/data/adventureThemes";
import type { AdventureConfig, AdventureOutline, SessionFeatures, StyleChoice, ThemeChoice } from "@/types/api";

const props = withDefaults(
  defineProps<{
    /** The choices of a game that could not be started: setup resumes from them, keeping their outline. */
    previous?: AdventureConfig | null;
  }>(),
  { previous: null }
);

const emit = defineEmits<{
  "adventure-ready": [config: AdventureConfig];
  "back-to-selection": [];
}>();

const currentStep = ref<"theme" | "style" | "generating">("theme");
const selectedTheme = ref<string | null>(null);
const selectedStyle = ref<string | null>(null);
const {
  description: customDescription,
  error: customThemeError,
  validating: validatingCustomTheme,
  validated: validatedCustomTheme,
  acceptable: customThemeAcceptable,
  validate: validateCustomTheme,
  ensureValidated: ensureCustomThemeValidated,
  reset: resetCustomTheme,
  restore: restoreCustomTheme,
} = useCustomTheme();
const startingGame = ref(false);
const adventureOutline = ref<AdventureOutline | null>(null);
// Why the adventure could not be designed; the player may try again or play without a design
const generationError = ref("");
const {
  status: designStatus,
  progress: designProgress,
  designed: designedOutline,
  design,
  keep,
} = useAdventureDesign();
const {
  flags: featureFlags,
  loading: checkingServer,
  failed: serverUnavailable,
  load: loadFeatureFlags,
  offered,
} = useFeatureFlags();
const features = ref<SessionFeatures>({ image: true, music: true, tts: true, stt: true });
// Whether the designed adventure is added to the adventure library, to be played again
const keepAdventure = ref(false);
// The library id of the outline, once kept
let keptAdventureId: string | undefined;

// Resume from a game that could not be started, with its outline (so it is not designed again)
const previous = props.previous;
if (previous) {
  selectedTheme.value = previous.theme.id;
  if (previous.theme.id === CUSTOM_THEME_ID) restoreCustomTheme(previous.theme.prompt);
  selectedStyle.value = previous.style.id;
  adventureOutline.value = previous.outline;
  keepAdventure.value = !!previous.adventureId;
  keptAdventureId = previous.adventureId;
  currentStep.value = "style";
}

const canProceedFromTheme = computed(() =>
  selectedTheme.value === CUSTOM_THEME_ID ? customThemeAcceptable.value : selectedTheme.value !== null
);

const selectTheme = (themeId: string) => {
  selectedTheme.value = themeId;
  if (themeId !== CUSTOM_THEME_ID) resetCustomTheme();
};

// Another theme or style needs another design
watch([selectedTheme, selectedStyle, validatedCustomTheme], () => {
  adventureOutline.value = null;
  keptAdventureId = undefined;
});

const proceedToStyle = async () => {
  // The validation started when the description lost focus may still be running
  if (selectedTheme.value === CUSTOM_THEME_ID && !(await ensureCustomThemeValidated())) return;
  currentStep.value = "style";
};

const goBackToStyle = () => {
  generationError.value = "";
  currentStep.value = "style";
};

// Fetch the server's feature flags on mount; an answer also shows the server is available
onMounted(async () => {
  await loadFeatureFlags();
  // A feature the server cannot provide starts switched off; a resumed setup keeps the player's choices
  const wanted = previous?.sessionFeatures ?? features.value;
  features.value = {
    image: wanted.image && offered("image"),
    music: wanted.music && offered("music"),
    tts: wanted.tts && offered("tts"),
    stt: wanted.stt && offered("stt"),
  };
});

const themeChoice = (): ThemeChoice | undefined => {
  if (selectedTheme.value === CUSTOM_THEME_ID) {
    return {
      id: CUSTOM_THEME_ID,
      name: "Custom Adventure",
      prompt: validatedCustomTheme.value || customDescription.value,
    };
  }
  const theme = PREDEFINED_THEMES.find((t) => t.id === selectedTheme.value);
  return theme && { id: theme.id, name: theme.name, prompt: theme.prompt };
};

const styleChoice = (): StyleChoice | undefined => {
  const style = ART_STYLES.find((s) => s.id === selectedStyle.value);
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
      image: features.value.image && offered("image"),
      music: features.value.music && offered("music"),
      tts: features.value.tts && offered("tts"),
      stt: features.value.stt && offered("stt"),
    },
  });
};

/** Start the game without a designed adventure: the Dungeon Master makes it up as it goes. */
const playWithoutOutline = () => {
  generationError.value = "";
  emitReady(null);
};

const startAdventure = async () => {
  const theme = themeChoice();
  const style = styleChoice();
  if (!theme || !style || serverUnavailable.value) return;

  startingGame.value = true;
  generationError.value = "";
  try {
    // An outline kept from a game that could not be started is not designed again
    let outline = adventureOutline.value;
    if (!outline) {
      currentStep.value = "generating";
      outline = await design(theme, style);
      adventureOutline.value = outline;
    }
    // A kept adventure is played from the library, like any ready-made one
    if (keepAdventure.value && !keptAdventureId) keptAdventureId = await keep(outline, theme, style);
    emitReady(outline, keepAdventure.value ? keptAdventureId : undefined);
  } catch (error) {
    console.error("Error generating adventure:", error);
    generationError.value = error instanceof Error ? error.message : String(error);
  } finally {
    startingGame.value = false;
  }
};
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
    transform: translateY(10px);
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
