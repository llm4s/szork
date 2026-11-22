<template>
  <div class="game-output-wrapper">
    <div class="game-output" ref="output">
      <div
        v-for="(message, index) in messages"
        :key="index"
        class="game-message"
        :class="message.isUser ? 'user' : 'game'"
      >
        <div v-if="message.image || message.imageLoading" class="scene-image-container">
          <img
            v-if="message.image"
            :src="'data:image/png;base64,' + message.image"
            alt="Scene visualization"
            class="scene-image"
          />
          <div v-else-if="message.imageLoading" class="image-loading">
            <v-progress-circular indeterminate color="primary" size="40" width="3"></v-progress-circular>
          </div>
        </div>
        <div class="message-text">
          {{ message.isUser ? `> ${message.text}` : message.text }}
          <span v-if="message.isStreaming" class="streaming-cursor">▊</span>
          <div v-if="message.scene?.exits?.length" class="exits-info">
            <strong>Exits:</strong>
            <span v-for="(exit, exitIndex) in message.scene.exits" :key="exitIndex">
              {{ exit.direction }}
              <span v-if="exit.description">({{ exit.description }})</span>
              <span v-if="exitIndex < message.scene.exits.length - 1">,</span>
            </span>
          </div>
        </div>
      </div>
      <div v-if="loading" class="loading-indicator">
        <v-progress-circular indeterminate color="primary" size="20" width="2" class="mr-2"></v-progress-circular>
        <span>Thinking...</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/** The game's transcript: commands, responses with their scene images and exits, and a "Thinking..." line. */
import { nextTick, ref } from "vue";
import type { GameMessage } from "@/composables/useWebSocketGame";

defineProps<{
  messages: GameMessage[];
  /** Waiting for a response. */
  loading: boolean;
}>();

const output = ref<HTMLElement>();

/** Scroll to the latest message once the DOM has updated. */
const scrollToBottom = async () => {
  await nextTick();
  if (output.value) output.value.scrollTop = output.value.scrollHeight;
};

defineExpose({ scrollToBottom });
</script>

<style scoped>
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

.exits-info {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #4caf50;
  font-size: 0.95em;
}

/* Streaming cursor animation */
.streaming-cursor {
  display: inline-block;
  animation: blink 1s infinite;
  color: #4caf50;
  font-weight: bold;
}

@keyframes blink {
  0%,
  50% {
    opacity: 1;
  }
  51%,
  100% {
    opacity: 0;
  }
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
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
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
