<template>
  <!-- The connection dropped: reconnecting by itself, or given up until the player retries -->
  <v-alert
    v-if="state === 'reconnecting' || state === 'closed'"
    class="connection-alert mx-4 mb-2"
    :type="state === 'reconnecting' ? 'info' : 'error'"
    variant="tonal"
    density="compact"
    role="status"
  >
    {{
      state === "reconnecting" ? "Lost the connection to the server. Reconnecting..." : "Not connected to the server."
    }}
    <template v-if="state === 'closed'" v-slot:append>
      <v-btn variant="text" size="small" class="retry-connection" @click="emit('retry')">Retry</v-btn>
    </template>
  </v-alert>
</template>

<script setup lang="ts">
/** Says when the game's connection is down, and offers to retry once it has stopped trying by itself. */
import type { ConnectionState } from "@/services/WebSocketClient";

defineProps<{ state: ConnectionState }>();

const emit = defineEmits<{ retry: [] }>();
</script>

<style scoped>
/* A line above the command field, not a share of the transcript's height */
.connection-alert {
  flex: 0 0 auto;
}
</style>
