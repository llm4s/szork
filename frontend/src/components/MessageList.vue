<template>
  <div class="game-output-wrapper">
    <div class="game-output" ref="output" role="log" aria-live="polite" aria-label="Story" @scroll="onScroll">
      <div
        v-for="(message, index) in shown"
        :key="index"
        class="game-message"
        :class="message.isUser ? 'user' : 'game'"
      >
        <div v-if="message.image || message.imageLoading" class="scene-image-container">
          <button
            v-if="message.image"
            type="button"
            class="scene-image-button"
            title="Click to enlarge"
            aria-label="Enlarge the picture"
            @click="enlarged = message"
          >
            <img :src="message.image" :alt="pictureAlt(message)" class="scene-image" />
          </button>
          <div v-else-if="message.imageLoading" class="image-loading">
            <v-progress-circular indeterminate color="primary" size="40" width="3"></v-progress-circular>
          </div>
        </div>
        <div class="message-text">
          <span class="narration">{{ message.isUser ? `> ${message.text}` : message.text }}</span>
          <span v-if="message.isStreaming" class="streaming-cursor">▊</span>
          <div v-if="message.scene?.exits?.length" class="exits-info">
            <strong>Exits:</strong>
            {{ exitsText(message.scene.exits) }}
          </div>
        </div>
      </div>
      <div v-if="opening && messages.length === 0" class="loading-indicator opening-indicator">
        <v-progress-circular indeterminate color="primary" size="20" width="2" class="mr-2"></v-progress-circular>
        <span>{{ opening }}</span>
      </div>
      <div v-if="thinking" class="loading-indicator">
        <v-progress-circular indeterminate color="primary" size="20" width="2" class="mr-2"></v-progress-circular>
        <span>Thinking...</span>
      </div>
    </div>

    <!-- A scene picture at full size; a click anywhere closes it -->
    <v-dialog :model-value="!!enlarged" @update:model-value="enlarged = undefined" max-width="1024">
      <img
        v-if="enlarged"
        :src="enlarged.image"
        :alt="pictureAlt(enlarged)"
        class="scene-image-enlarged"
        @click="enlarged = undefined"
      />
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
/** The game's transcript: commands, responses with their scene images and exits, and a "Thinking..." line. */
import { computed, nextTick, ref } from "vue";
import type { GameMessage } from "@/composables/useWebSocketGame";
import type { ExitData } from "@/types/WebSocketProtocol";

/** A scene's exits as one line: "north (a dark passage), east". */
const exitsText = (exits: ExitData[]): string =>
  exits.map((exit) => (exit.description ? `${exit.direction} (${exit.description})` : exit.direction)).join(", ");

// How close to the bottom (in pixels) still counts as reading the latest message
const AT_BOTTOM_SLACK = 48;

const props = defineProps<{
  messages: GameMessage[];
  /** Waiting for a response. */
  loading: boolean;
  /** What to say while the game is opening and there is nothing to show yet (e.g. "Starting your adventure..."). */
  opening?: string;
}>();

const output = ref<HTMLElement>();
// The message whose picture the player clicked on, shown at full size
const enlarged = ref<GameMessage>();
// Whether the player is at the latest message (rather than scrolled up to read an earlier one)
let atBottom = true;

// A streamed response is shown once its first words arrive; until then the "Thinking..." line stands for it
const shown = computed(() => props.messages.filter((m) => !(m.isStreaming && !m.text)));
const thinking = computed(() => props.loading && !props.messages.some((m) => m.isStreaming && m.text));

const pictureAlt = (message: GameMessage) =>
  message.scene?.locationName ? `Picture of ${message.scene.locationName}` : "Picture of the scene";

const onScroll = () => {
  const el = output.value;
  if (el) atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_SLACK;
};

/**
 * Scroll to the latest message once the DOM has updated. Unless `force`d (the player just sent a
 * command), a player who has scrolled up to read is left where they are.
 */
const scrollToBottom = async (force = false) => {
  await nextTick();
  if (!output.value || (!force && !atBottom)) return;
  output.value.scrollTop = output.value.scrollHeight;
  atBottom = true;
};

defineExpose({ scrollToBottom });
</script>

<style scoped>
.game-output-wrapper {
  flex: 1;
  overflow: hidden;
  background-color: #121212;
  border-radius: 4px;
  margin-bottom: 1rem;
}

.game-output {
  height: 100%;
  overflow-y: auto;
  /* Menlo and Consolas have sturdier strokes than Courier New, whose thin ones are hard to read on a dark ground */
  font-family: "SF Mono", Menlo, Consolas, "DejaVu Sans Mono", "Courier New", monospace;
  font-size: 1.05rem;
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
  color: #90caf9;
}

.game-message.game {
  color: #c8f5cb;
}

.exits-info {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  color: #81e086;
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
  color: #bdbdbd;
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
  width: 320px;
  height: 320px;
  order: -1; /* Places image on the left */
}

.scene-image-button {
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border: none;
  background: none;
  cursor: zoom-in;
}

.scene-image-button:focus-visible {
  outline: 2px solid #90caf9;
  outline-offset: -2px;
}

.scene-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  border-radius: 8px;
}

.scene-image-enlarged {
  display: block;
  width: 100%;
  max-height: 90vh;
  object-fit: contain;
  border-radius: 8px;
  cursor: zoom-out;
}

.message-text {
  flex: 1;
}

/* Keeps the narration's paragraphs and line breaks */
.narration {
  white-space: pre-wrap;
}

/* On a narrow screen the picture goes above its text, as wide as the transcript */
@media (max-width: 600px) {
  .game-message {
    flex-direction: column;
  }

  .scene-image-container {
    width: 100%;
    height: auto;
    aspect-ratio: 1;
  }
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
