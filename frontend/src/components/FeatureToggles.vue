<template>
  <div class="session-flags">
    <v-switch
      density="compact"
      hide-details
      class="mr-2 keep-adventure"
      v-model="keepAdventure"
      label="Keep adventure"
      title="Add this adventure to the library, to play again; its pictures are then made once for every game of it"
    />
    <v-switch
      v-for="toggle in toggles"
      :key="toggle.feature"
      density="compact"
      hide-details
      class="mr-2"
      :class="`feature-${toggle.feature}`"
      :model-value="features[toggle.feature]"
      :disabled="featureStatus(flags[toggle.feature]) !== 'Enabled'"
      :label="featureLabel(toggle.name, flags[toggle.feature])"
      @update:model-value="(on) => set(toggle.feature, !!on)"
    />
  </div>
</template>

<script setup lang="ts">
/** The switches for a new game's optional features, and for keeping its adventure in the library. */
import { featureLabel, featureStatus, type SessionFeature } from "@/composables/useFeatureFlags";
import type { FeatureFlags, SessionFeatures } from "@/types/api";

defineProps<{
  /** The server's features: one it cannot provide cannot be switched on. */
  flags: FeatureFlags;
}>();

const features = defineModel<SessionFeatures>("features", { required: true });
const keepAdventure = defineModel<boolean>("keepAdventure", { required: true });

const toggles: { feature: SessionFeature; name: string }[] = [
  { feature: "image", name: "Images" },
  { feature: "music", name: "Music" },
  { feature: "tts", name: "TTS" },
  { feature: "stt", name: "STT" },
];

const set = (feature: SessionFeature, on: boolean) => {
  features.value = { ...features.value, [feature]: on };
};
</script>
