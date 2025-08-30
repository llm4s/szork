<template>
  <v-app>
    <v-main>
      <router-view />
    </v-main>
  </v-app>
</template>

<script lang="ts">
import { defineComponent, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";

export default defineComponent({
  name: "App",
  setup() {
    const router = useRouter();
    
    const handleServerInstanceChange = (event: Event) => {
      const customEvent = event as CustomEvent;
      console.log('[App] Server instance changed:', customEvent.detail);
      // Navigate to home page
      router.push('/').then(() => {
        // Show a notification if available
        if ((window as any).$toast) {
          (window as any).$toast.warning('Server restarted. Please start a new adventure.');
        }
      });
    };
    
    onMounted(() => {
      window.addEventListener('serverInstanceChanged', handleServerInstanceChange);
    });
    
    onUnmounted(() => {
      window.removeEventListener('serverInstanceChanged', handleServerInstanceChange);
    });
    
    return {};
  }
});</script>