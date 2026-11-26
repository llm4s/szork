<template>
  <div class="setup-step generating-adventure">
    <h2 class="setup-title">Crafting Your Adventure</h2>

    <div class="generation-container">
      <div v-if="error" class="generation-failed" role="alert">
        <p class="failure-text">The adventure could not be designed: {{ error }}</p>
        <div class="failure-actions">
          <v-btn variant="outlined" class="retry-generation" @click="emit('retry')">
            <v-icon start>mdi-refresh</v-icon>
            Try again
          </v-btn>
          <v-btn variant="text" class="play-without-outline" @click="emit('play-without-outline')">
            Play without a designed adventure
          </v-btn>
          <v-btn variant="text" @click="emit('back')">Back</v-btn>
        </div>
      </div>
      <template v-else>
        <div class="loading-animation" aria-hidden="true">
          <div class="spinner"></div>
          <div class="pulse-ring"></div>
        </div>
        <div class="generation-status" role="status">
          <p class="status-text">{{ status }}</p>
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: progress + '%' }"></div>
          </div>
        </div>
      </template>

      <div v-if="outline" class="adventure-preview">
        <h3>{{ outline.title }}</h3>
        <p class="quest-preview">{{ outline.mainQuest }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/** The screen shown while an adventure is designed, or the choices offered when it could not be. */
import type { AdventureOutline } from "@/types/api";

defineProps<{
  /** What the designer is doing, as far as the player is told. */
  status: string;
  /** Percent done. */
  progress: number;
  /** Why the adventure could not be designed; empty while it is being designed. */
  error: string;
  /** The design, once there is one. */
  outline: AdventureOutline | null;
}>();

const emit = defineEmits<{
  retry: [];
  /** Start the game with no design: the Dungeon Master makes it up as it goes. */
  "play-without-outline": [];
  back: [];
}>();
</script>

<style scoped>
.generating-adventure {
  text-align: center;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 2rem;
  animation: fadeIn 0.5s ease-in;
}

.setup-title {
  text-align: center;
  font-size: 2.5rem;
  margin: 0;
  margin-bottom: 1rem;
  color: #fff;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
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

@media (max-width: 768px) {
  .setup-title {
    font-size: 1.8rem;
    margin-bottom: 0.75rem;
  }
}
</style>
