<template>
  <div class="audio-controls">
    <v-tooltip text="Back to Adventure Selection" location="bottom">
      <template v-slot:activator="{ props }">
        <v-btn
          v-bind="props"
          @click="emit('back')"
          color="warning"
          variant="outlined"
          icon
          size="small"
          class="mr-2 back-btn"
          aria-label="Back to adventure selection"
        >
          <v-icon>mdi-arrow-left</v-icon>
        </v-btn>
      </template>
    </v-tooltip>
    <v-tooltip
      :text="
        imageAvailable
          ? imageGeneration
            ? 'Disable image generation'
            : 'Enable image generation'
          : 'Image generation disabled by server'
      "
      location="bottom"
    >
      <template v-slot:activator="{ props }">
        <v-btn
          v-bind="props"
          @click="imageAvailable && (imageGeneration = !imageGeneration)"
          :color="imageGeneration && imageAvailable ? 'info' : 'grey'"
          :variant="imageGeneration && imageAvailable ? 'flat' : 'outlined'"
          :disabled="!imageAvailable"
          icon
          size="small"
          class="mr-2 image-toggle"
          aria-label="Image generation"
          :aria-pressed="imageGeneration && imageAvailable"
        >
          <v-icon>{{ imageGeneration ? "mdi-image" : "mdi-image-off" }}</v-icon>
        </v-btn>
      </template>
    </v-tooltip>
    <v-tooltip
      :text="
        musicAvailable
          ? backgroundMusic
            ? 'Disable background music'
            : 'Enable background music'
          : 'Music disabled by server'
      "
      location="bottom"
    >
      <template v-slot:activator="{ props }">
        <v-btn
          v-bind="props"
          @click="musicAvailable && (backgroundMusic = !backgroundMusic)"
          :color="backgroundMusic && musicAvailable ? 'primary' : 'grey'"
          :variant="backgroundMusic && musicAvailable ? 'flat' : 'outlined'"
          :disabled="!musicAvailable"
          icon
          size="small"
          class="mr-2 music-toggle"
          aria-label="Background music"
          :aria-pressed="backgroundMusic && musicAvailable"
        >
          <v-icon>{{ backgroundMusic ? "mdi-music" : "mdi-music-off" }}</v-icon>
        </v-btn>
      </template>
    </v-tooltip>
    <v-tooltip
      :text="
        ttsAvailable ? (narration ? 'Disable voice narration' : 'Enable voice narration') : 'TTS disabled by server'
      "
      location="bottom"
    >
      <template v-slot:activator="{ props }">
        <v-btn
          v-bind="props"
          @click="ttsAvailable && (narration = !narration)"
          :color="narration && ttsAvailable ? 'success' : 'grey'"
          :variant="narration && ttsAvailable ? 'flat' : 'outlined'"
          :disabled="!ttsAvailable"
          icon
          size="small"
          class="narration-toggle"
          aria-label="Voice narration"
          :aria-pressed="narration && ttsAvailable"
        >
          <v-icon>{{ narration ? "mdi-account-voice" : "mdi-account-voice-off" }}</v-icon>
        </v-btn>
      </template>
    </v-tooltip>
  </div>
</template>

<script setup lang="ts">
/** The game screen's back button and the image, music and narration switches. */
defineProps<{
  /** Whether the server provides each feature in this session; a feature it does not provide cannot be switched on. */
  imageAvailable: boolean;
  musicAvailable: boolean;
  ttsAvailable: boolean;
}>();

const imageGeneration = defineModel<boolean>("imageGeneration", { required: true });
const backgroundMusic = defineModel<boolean>("backgroundMusic", { required: true });
const narration = defineModel<boolean>("narration", { required: true });

const emit = defineEmits<{ back: [] }>();
</script>

<style scoped>
.audio-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
