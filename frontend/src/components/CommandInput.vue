<template>
  <div class="game-input-wrapper">
    <v-text-field
      v-model="command"
      @keyup.enter="emit('submit')"
      placeholder="Enter your command..."
      aria-label="Your command"
      :maxlength="MAX_COMMAND_CHARS"
      :counter="nearCommandLimit ? MAX_COMMAND_CHARS : undefined"
      :persistent-counter="nearCommandLimit"
      :hide-details="!nearCommandLimit"
      :disabled="disabled"
      variant="outlined"
      density="compact"
      class="game-input"
      autofocus
    >
      <template v-slot:append-inner>
        <v-tooltip text="Hold to capture speech" location="top">
          <template v-slot:activator="{ props: tooltip }">
            <button
              v-bind="tooltip"
              type="button"
              aria-label="Hold to speak a command"
              :aria-pressed="recording"
              @keydown.space.prevent="startRecording"
              @keydown.enter.prevent="startRecording"
              @keyup.space.prevent="stopRecording"
              @keyup.enter.prevent="stopRecording"
              @blur="stopRecording"
              @mousedown.prevent="startRecording"
              @mouseup.prevent="stopRecording"
              @mouseleave.prevent="stopRecording"
              @touchstart.prevent="startRecording"
              @touchend.prevent="stopRecording"
              @touchcancel.prevent="stopRecording"
              :disabled="loading || !speechEnabled || disabled"
              class="audio-record-btn mr-1"
              :class="{ recording }"
            >
              <v-icon>mdi-microphone</v-icon>
            </button>
          </template>
        </v-tooltip>
        <v-tooltip text="Submit text command" location="top">
          <template v-slot:activator="{ props: tooltip }">
            <v-btn
              v-bind="tooltip"
              @click="emit('submit')"
              color="primary"
              variant="text"
              icon="mdi-send"
              aria-label="Send command"
              :disabled="!command.trim() || loading || disabled"
              :loading="loading"
            ></v-btn>
          </template>
        </v-tooltip>
      </template>
    </v-text-field>
  </div>
</template>

<script setup lang="ts">
/**
 * The command line: a typed command (sent with Enter or the send button) or a spoken one (recorded while
 * the microphone button is held).
 */
import { computed } from "vue";
import { useVoiceRecorder } from "@/composables/useVoiceRecorder";
import { MAX_COMMAND_CHARS, nearLimit } from "@/data/limits";

defineProps<{
  /** Waiting for the answer to the last command: no other can be sent. */
  loading: boolean;
  /** Whether the session takes spoken commands. */
  speechEnabled: boolean;
  /** This tab no longer plays the game. */
  disabled: boolean;
}>();

const command = defineModel<string>({ required: true });

const emit = defineEmits<{
  /** The player sent the typed command. */
  submit: [];
  /** A recording finished (it may be empty, if the button was only tapped). */
  recorded: [audio: Blob];
  /** The microphone could not be used. */
  "microphone-error": [];
}>();

// Near the length limit, the field shows how much is left
const nearCommandLimit = computed(() => nearLimit(command.value.length, MAX_COMMAND_CHARS));

const { recording, startRecording, stopRecording } = useVoiceRecorder({
  onRecorded: (audio) => emit("recorded", audio),
  onError: () => emit("microphone-error"),
  log: import.meta.env.DEV ? (message, ...args) => console.log(`[Voice] ${message}`, ...args) : undefined,
});
</script>

<style scoped>
.game-input-wrapper {
  flex-shrink: 0;
  background-color: #121212;
  padding: 0.5rem 0;
}

.game-input {
  font-family: "Courier New", Courier, monospace;
  background-color: #1e1e1e !important;
}

.audio-record-btn {
  background: transparent;
  border: none;
  padding: 8px;
  cursor: pointer;
  border-radius: 50%;
  transition: all 0.2s;
  color: #f44336;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.audio-record-btn:hover:not(:disabled) {
  background-color: rgba(244, 67, 54, 0.1);
}

.audio-record-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.audio-record-btn.recording {
  background-color: #f44336;
  color: white;
  animation: recording-pulse 1s ease-in-out infinite;
}

@keyframes recording-pulse {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.8;
    transform: scale(1.1);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
