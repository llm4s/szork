<template>
  <div class="theme-grid" role="radiogroup" aria-label="Adventure theme">
    <div
      v-for="theme in themes"
      :key="theme.id"
      class="theme-card"
      :class="{ selected: selected === theme.id }"
      role="radio"
      :aria-checked="selected === theme.id"
      tabindex="0"
      @click="emit('select', theme.id)"
      @keydown.enter.prevent="emit('select', theme.id)"
      @keydown.space.prevent="emit('select', theme.id)"
    >
      <div class="theme-icon" aria-hidden="true">{{ theme.icon }}</div>
      <h3 class="theme-name">{{ theme.name }}</h3>
      <p class="theme-description">{{ theme.description }}</p>
    </div>

    <!-- Custom Theme Option -->
    <div
      class="theme-card custom-theme"
      :class="{ selected: selected === CUSTOM_THEME_ID }"
      role="radio"
      :aria-checked="selected === CUSTOM_THEME_ID"
      tabindex="0"
      @click="emit('select', CUSTOM_THEME_ID)"
      @keydown.enter.prevent="emit('select', CUSTOM_THEME_ID)"
      @keydown.space.prevent="emit('select', CUSTOM_THEME_ID)"
    >
      <div class="theme-icon" aria-hidden="true">✨</div>
      <h3 class="theme-name">Custom Adventure</h3>
      <p class="theme-description">Create your own unique adventure setting</p>
    </div>
  </div>

  <!-- Custom Theme Input -->
  <div v-if="selected === CUSTOM_THEME_ID" class="custom-theme-input">
    <v-text-field
      v-model="customTheme"
      label="Describe your adventure theme"
      placeholder="e.g., A steampunk airship exploring floating islands..."
      variant="outlined"
      :maxlength="MAX_THEME_CHARS"
      :counter="nearThemeLimit ? MAX_THEME_CHARS : undefined"
      :persistent-counter="nearThemeLimit"
      :error-messages="customThemeError"
      :loading="validating"
      @blur="emit('validate')"
    />
  </div>
</template>

<script setup lang="ts">
/** The grid of adventure themes, plus a free-text description when the custom theme is chosen. */
import { computed } from "vue";
import { CUSTOM_THEME_ID, type Theme } from "@/data/adventureThemes";
import { MAX_THEME_CHARS, nearLimit } from "@/data/limits";

defineProps<{
  themes: Theme[];
  /** The chosen theme's id, or CUSTOM_THEME_ID. */
  selected: string | null;
  customThemeError: string;
  /** The custom description is being validated. */
  validating: boolean;
}>();

const customTheme = defineModel<string>("customTheme", { required: true });

// Near the length limit, the field shows how much is left
const nearThemeLimit = computed(() => nearLimit(customTheme.value.length, MAX_THEME_CHARS));

const emit = defineEmits<{
  select: [themeId: string];
  /** The custom description lost focus: time to validate it. */
  validate: [];
}>();
</script>

<style scoped>
.theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.theme-card {
  background: rgba(255, 255, 255, 0.05);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.theme-card:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.theme-card:focus-visible {
  outline: 2px solid #90caf9;
  outline-offset: 2px;
}

.theme-card.selected {
  background: rgba(33, 150, 243, 0.2);
  border-color: #2196f3;
  box-shadow: 0 0 20px rgba(33, 150, 243, 0.3);
}

.theme-icon {
  font-size: 3rem;
  text-align: center;
  margin-bottom: 1rem;
}

.theme-name {
  font-size: 1.3rem;
  margin-bottom: 0.5rem;
  color: #fff;
}

.theme-description {
  font-size: 0.9rem;
  color: rgba(255, 255, 255, 0.7);
  line-height: 1.4;
}

/* Always visible when the custom theme is selected */
.custom-theme-input {
  max-width: 600px;
  margin: 2rem auto;
  position: relative;
  z-index: 10;
}

@media (max-width: 1024px) {
  .theme-grid {
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 1rem;
  }
}

@media (max-width: 768px) {
  .theme-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 0.75rem;
  }

  .theme-card {
    padding: 1rem;
  }

  .theme-icon {
    font-size: 2rem;
    margin-bottom: 0.5rem;
  }

  .theme-name {
    font-size: 1.1rem;
  }

  .theme-description {
    font-size: 0.85rem;
  }
}

@media (max-width: 480px) {
  .theme-grid {
    grid-template-columns: 1fr;
  }

  .theme-card {
    padding: 1.25rem;
  }
}
</style>
