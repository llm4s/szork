<template>
  <div class="intro-screen">
    <div class="intro-content">
      <img src="/SZork_intro.webp" alt="Welcome to Szork" class="intro-image" />
      <v-btn size="x-large" class="szork-button szork-button--large begin-button" @click="emit('begin')">
        Begin Your Adventure
        <v-icon end>mdi-chevron-right</v-icon>
      </v-btn>
    </div>
  </div>
</template>

<script setup lang="ts">
/** The title screen, with the way in to the game list. */
const emit = defineEmits<{ begin: [] }>();
</script>

<style scoped>
.intro-screen {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  position: relative;
  overflow: hidden;
}

.intro-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3rem;
  animation: fadeIn 1s ease-in;
}

.intro-image {
  max-width: 90%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.8);
}

.begin-button {
  font-size: 1.2rem;
  animation: pulse-shadow 2s ease-in-out infinite;
}

@keyframes pulse-shadow {
  0%,
  100% {
    box-shadow:
      inset 2px 2px 4px rgba(255, 255, 255, 0.2),
      inset -2px -2px 4px rgba(0, 0, 0, 0.5),
      0 4px 8px rgba(0, 0, 0, 0.3),
      0 0 20px rgba(255, 255, 255, 0.2);
  }
  50% {
    box-shadow:
      inset 2px 2px 4px rgba(255, 255, 255, 0.2),
      inset -2px -2px 4px rgba(0, 0, 0, 0.5),
      0 4px 8px rgba(0, 0, 0, 0.3),
      0 0 40px rgba(255, 255, 255, 0.4);
  }
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
</style>
