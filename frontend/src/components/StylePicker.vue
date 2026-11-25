<template>
  <div class="style-grid" role="radiogroup" aria-label="Art style">
    <div
      v-for="style in styles"
      :key="style.id"
      class="style-card"
      :class="{ selected: selected === style.id }"
      role="radio"
      :aria-checked="selected === style.id"
      tabindex="0"
      @click="emit('select', style.id)"
      @keydown.enter.prevent="emit('select', style.id)"
      @keydown.space.prevent="emit('select', style.id)"
    >
      <div class="style-preview">
        <img
          v-if="style.sampleImage"
          :src="style.sampleImage"
          :alt="style.name + ' sample'"
          class="style-sample-image"
        />
        <div v-else class="style-gradient" :style="{ background: style.gradient }"></div>
      </div>
      <h3 class="style-name">{{ style.name }}</h3>
      <p class="style-description">{{ style.description }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
/** The grid of art styles for the scene images, each with a sample. */
import type { ArtStyle } from "@/data/adventureThemes";

defineProps<{
  styles: ArtStyle[];
  /** The chosen style's id. */
  selected: string | null;
}>();

const emit = defineEmits<{ select: [styleId: string] }>();
</script>

<style scoped>
.style-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.style-card {
  background: rgba(255, 255, 255, 0.05);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.style-card:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.style-card:focus-visible {
  outline: 2px solid #90caf9;
  outline-offset: 2px;
}

.style-card.selected {
  background: rgba(33, 150, 243, 0.2);
  border-color: #2196f3;
  box-shadow: 0 0 20px rgba(33, 150, 243, 0.3);
}

.style-name {
  font-size: 1.3rem;
  margin-bottom: 0.5rem;
  color: #fff;
}

.style-description {
  font-size: 0.9rem;
  color: rgba(255, 255, 255, 0.7);
  line-height: 1.4;
}

.style-preview {
  height: 150px;
  border-radius: 8px;
  margin-bottom: 1rem;
  overflow: hidden;
  background: #1a1a1a;
}

.style-sample-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.style-gradient {
  width: 100%;
  height: 100%;
  box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.3);
}

@media (max-width: 1024px) {
  .style-grid {
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 1rem;
  }
}

@media (max-width: 768px) {
  .style-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 0.75rem;
  }

  .style-card {
    padding: 1rem;
  }

  .style-name {
    font-size: 1.1rem;
  }

  .style-description {
    font-size: 0.85rem;
  }
}

@media (max-width: 480px) {
  .style-grid {
    grid-template-columns: 1fr;
  }

  .style-card {
    padding: 1.25rem;
  }
}
</style>
