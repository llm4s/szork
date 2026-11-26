<template>
  <div class="game-selection-screen">
    <div class="selection-content">
      <h1 class="selection-title">SZork Adventures</h1>
      <!-- Capability Badges -->
      <div class="capabilities" v-if="featureFlags">
        <v-chip class="mr-2 mb-2" :color="featureFlags.llm.available ? 'primary' : 'grey'" variant="outlined" label>
          LLM: {{ featureFlags.llm.provider || "Unavailable" }}
        </v-chip>
        <v-chip
          class="mr-2 mb-2"
          :color="featureFlags.image.enabled && featureFlags.image.available ? 'info' : 'grey'"
          variant="outlined"
          label
        >
          Images: {{ featureFlags.image.enabled ? featureFlags.image.provider : "Disabled" }}
        </v-chip>
        <v-chip
          class="mr-2 mb-2"
          :color="featureFlags.music.enabled && featureFlags.music.available ? 'primary' : 'grey'"
          variant="outlined"
          label
        >
          Music:
          {{ featureFlags.music.enabled ? (featureFlags.music.available ? "Enabled" : "Unavailable") : "Disabled" }}
        </v-chip>
        <v-chip
          class="mr-2 mb-2"
          :color="featureFlags.tts.enabled && featureFlags.tts.available ? 'success' : 'grey'"
          variant="outlined"
          label
        >
          TTS: {{ featureFlags.tts.enabled ? (featureFlags.tts.available ? "Enabled" : "Unavailable") : "Disabled" }}
        </v-chip>
        <v-chip
          class="mr-2 mb-2"
          :color="featureFlags.stt.enabled && featureFlags.stt.available ? 'success' : 'grey'"
          variant="outlined"
          label
        >
          STT: {{ featureFlags.stt.enabled ? (featureFlags.stt.available ? "Enabled" : "Unavailable") : "Disabled" }}
        </v-chip>
      </div>

      <v-btn size="x-large" class="new-game-button" @click="$emit('start-new-game')">
        <v-icon start>mdi-plus-circle</v-icon>
        Start New Adventure
      </v-btn>

      <div v-if="adventures.length > 0" class="saved-games-section ready-made-section">
        <h2 class="section-title">Ready-Made Adventures</h2>
        <div class="games-grid">
          <v-card
            v-for="adventure in adventures"
            :key="adventure.id"
            class="game-card adventure-card"
            @click="playAdventure(adventure)"
            hover
          >
            <v-card-title class="game-title">{{ adventure.title }}</v-card-title>
            <v-card-subtitle v-if="adventure.tagline" class="game-theme">{{ adventure.tagline }}</v-card-subtitle>
            <v-card-text>
              <p class="adventure-quest">{{ adventure.mainQuest }}</p>
              <div class="game-stats">
                <div class="stat">
                  <v-icon small>mdi-map-marker-path</v-icon>
                  <span>{{ adventure.locations }} locations</span>
                </div>
                <div class="stat" v-if="adventure.artStyle">
                  <v-icon small>mdi-palette</v-icon>
                  <span>{{ adventure.artStyle }} • {{ adventure.images }}/{{ adventure.locations }} pictured</span>
                </div>
              </div>
            </v-card-text>
            <v-card-actions>
              <v-btn variant="text" color="primary" @click.stop="playAdventure(adventure)">
                Play
                <v-icon end>mdi-play</v-icon>
              </v-btn>
            </v-card-actions>
          </v-card>
        </div>
      </div>

      <div v-if="loading" class="loading-container">
        <v-progress-circular indeterminate color="primary" size="40" width="3"></v-progress-circular>
      </div>

      <div v-else-if="gamesError" class="games-error" role="alert">
        <p>Could not fetch your saved adventures.</p>
        <v-btn variant="outlined" class="retry-games" @click="loadSavedGames">
          <v-icon start>mdi-refresh</v-icon>
          Try again
        </v-btn>
      </div>

      <div v-else-if="savedGames.length > 0" class="saved-games-section">
        <h2 class="section-title">Continue Your Adventures</h2>
        <div class="games-grid">
          <v-card v-for="game in savedGames" :key="game.gameId" class="game-card" @click="selectGame(game)" hover>
            <v-card-title class="game-title">
              {{ game.title }}
            </v-card-title>
            <v-card-subtitle class="game-theme">{{ game.theme }} • {{ game.artStyle }}</v-card-subtitle>
            <v-card-text>
              <div class="game-stats">
                <div class="stat">
                  <v-icon small>mdi-calendar</v-icon>
                  <span>Created: {{ formatDate(game.createdAt) }}</span>
                </div>
                <div class="stat">
                  <v-icon small>mdi-clock-outline</v-icon>
                  <span>Last Played: {{ formatRelativeDate(game.lastPlayed) }}</span>
                </div>
                <div class="stat">
                  <v-icon small>mdi-timer</v-icon>
                  <span>Play Time: {{ formatPlayTime(game.totalPlayTime) }}</span>
                </div>
              </div>
            </v-card-text>
            <v-card-actions>
              <v-btn variant="text" color="primary" @click.stop="selectGame(game)">
                Continue
                <v-icon end>mdi-play</v-icon>
              </v-btn>
              <v-spacer></v-spacer>
              <v-btn
                variant="text"
                color="error"
                icon
                @click.stop="confirmDelete(game)"
                title="Delete Save"
                :aria-label="`Delete ${game.title}`"
              >
                <v-icon>mdi-delete</v-icon>
              </v-btn>
            </v-card-actions>
          </v-card>
        </div>
      </div>

      <div v-else class="no-games">
        <p>No saved adventures found</p>
        <p class="subtitle">Start a new adventure to begin your journey!</p>
      </div>
    </div>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete Save Game?</v-card-title>
        <v-card-text>
          Are you sure you want to delete "{{ gameToDelete?.title }}"? This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn variant="text" @click="deleteDialog = false">Cancel</v-btn>
          <v-btn variant="text" color="error" @click="deleteGame">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar v-model="errorSnackbar" color="error" :timeout="6000" location="top">
      {{ errorMessage }}
      <template v-slot:actions>
        <v-btn variant="text" @click="errorSnackbar = false">Close</v-btn>
      </template>
    </v-snackbar>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted } from "vue";
import axios from "axios";
import { useFeatureFlags } from "@/composables/useFeatureFlags";
import type {
  AdventureSummary,
  AdventuresResponse,
  DeleteGameResponse,
  GamesResponse,
  SavedGameSummary,
} from "@/types/api";

export default defineComponent({
  name: "GameSelection",
  emits: ["start-new-game", "load-game", "play-adventure"],
  setup(props, { emit }) {
    const savedGames = ref<SavedGameSummary[]>([]);
    // The adventure library: adventures kept to be played again
    const adventures = ref<AdventureSummary[]>([]);
    const loading = ref(true);
    // The saved games could not be fetched (rather than there being none)
    const gamesError = ref(false);
    const { flags: featureFlags, load: loadFeatureFlags } = useFeatureFlags();
    const deleteDialog = ref(false);
    const gameToDelete = ref<SavedGameSummary | null>(null);
    const errorSnackbar = ref(false);
    const errorMessage = ref("");

    const showError = (text: string) => {
      errorMessage.value = text;
      errorSnackbar.value = true;
    };

    const loadSavedGames = async () => {
      loading.value = true;
      gamesError.value = false;
      try {
        const response = await axios.get<GamesResponse>("/api/games");
        if (response.data.status !== "success") throw new Error(response.data.message ?? "The server refused");
        savedGames.value = response.data.games.map((game) => ({
          ...game,
          title: game.title || "Untitled Adventure",
        }));
      } catch (error) {
        console.error("Error loading saved games:", error);
        gamesError.value = true;
      } finally {
        loading.value = false;
      }
    };

    const loadAdventures = async () => {
      try {
        const response = await axios.get<AdventuresResponse>("/api/adventures");
        if (response.data.status === "success") adventures.value = response.data.adventures;
      } catch (error) {
        console.warn("Unable to fetch the adventure library:", error);
      }
    };

    const playAdventure = (adventure: AdventureSummary) => {
      emit("play-adventure", adventure);
    };

    const formatDate = (timestamp: number) => {
      if (!timestamp || Number.isNaN(timestamp)) return "Unknown";
      const d = new Date(timestamp);
      if (isNaN(d.getTime())) return "Unknown";
      return d.toLocaleDateString("en-US", {
        month: "short",
        day: "numeric",
        year: "numeric",
      });
    };

    const formatRelativeDate = (timestamp: number) => {
      const now = Date.now();
      const diff = now - timestamp;
      const minutes = Math.floor(diff / 60000);
      const hours = Math.floor(diff / 3600000);
      const days = Math.floor(diff / 86400000);

      if (minutes < 1) return "Just now";
      if (minutes < 60) return `${minutes}m ago`;
      if (hours < 24) return `${hours}h ago`;
      if (days < 7) return `${days}d ago`;
      return formatDate(timestamp);
    };

    const formatPlayTime = (milliseconds: number) => {
      const totalSeconds = Math.floor(milliseconds / 1000);
      const hours = Math.floor(totalSeconds / 3600);
      const minutes = Math.floor((totalSeconds % 3600) / 60);

      if (hours > 0) {
        return `${hours}h ${minutes}m`;
      }
      return `${minutes}m`;
    };

    const selectGame = (game: SavedGameSummary) => {
      emit("load-game", game.gameId);
    };

    const confirmDelete = (game: SavedGameSummary) => {
      gameToDelete.value = game;
      deleteDialog.value = true;
    };

    const deleteGame = async () => {
      // The dialog may be closed (clearing gameToDelete) while the request is on its way
      const gameId = gameToDelete.value?.gameId;
      if (!gameId) return;

      try {
        const response = await axios.delete<DeleteGameResponse>(`/api/game/${encodeURIComponent(gameId)}`);
        if (response.data.status !== "success") throw new Error(response.data.error);
        savedGames.value = savedGames.value.filter((g) => g.gameId !== gameId);
        deleteDialog.value = false;
        gameToDelete.value = null;
      } catch (error) {
        console.error("Error deleting game:", error);
        showError("Failed to delete the game. Please try again.");
      }
    };

    onMounted(() => {
      loadSavedGames();
      loadAdventures();
      loadFeatureFlags();
    });

    return {
      savedGames,
      adventures,
      playAdventure,
      loading,
      gamesError,
      loadSavedGames,
      deleteDialog,
      featureFlags,
      errorSnackbar,
      errorMessage,
      gameToDelete,
      formatDate,
      formatRelativeDate,
      formatPlayTime,
      selectGame,
      confirmDelete,
      deleteGame,
    };
  },
});
</script>

<style scoped>
.game-selection-screen {
  width: 100%;
  min-height: 100vh;
  display: flex;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  padding: 2rem;
}

.selection-content {
  /* Centred when it fits; when it is taller than the window it starts at the top, so nothing is cut off above */
  margin: auto;
  width: 100%;
  max-width: 1200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2rem;
}

.selection-title {
  font-size: 3rem;
  font-weight: bold;
  color: #ffffff;
  text-align: center;
  margin-bottom: 1rem;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
}

.capabilities {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 1rem;
}

.new-game-button {
  background-color: #000000 !important;
  color: #ffffff !important;
  border: 2px solid #ffffff !important;
  border-radius: 8px !important;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.5px;
  padding: 0 32px !important;
  min-height: 56px !important;
  height: 56px !important;
  box-shadow:
    inset 2px 2px 4px rgba(255, 255, 255, 0.2),
    inset -2px -2px 4px rgba(0, 0, 0, 0.5),
    0 4px 8px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
}

.new-game-button:hover {
  background-color: #1a1a1a !important;
  transform: translateY(-2px);
  box-shadow:
    inset 2px 2px 6px rgba(255, 255, 255, 0.3),
    inset -2px -2px 6px rgba(0, 0, 0, 0.6),
    0 6px 12px rgba(0, 0, 0, 0.4);
}

.loading-container {
  padding: 3rem;
  display: flex;
  justify-content: center;
}

.saved-games-section {
  width: 100%;
}

.section-title {
  font-size: 1.5rem;
  color: #ffffff;
  margin-bottom: 1.5rem;
  text-align: center;
}

.games-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1.5rem;
  width: 100%;
}

.game-card {
  background-color: #1e1e1e !important;
  border: 1px solid #333;
  transition: all 0.3s ease;
  cursor: pointer;
}

.game-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  border-color: #555;
}

.game-title {
  font-size: 1.2rem;
  font-weight: 600;
  color: #ffffff;
}

.game-theme {
  color: #999 !important;
  font-size: 0.9rem;
}

.game-stats {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.stat {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: #ccc;
  font-size: 0.9rem;
}

.stat .v-icon {
  color: #777;
}

.adventure-quest {
  margin-bottom: 0.75rem;
  opacity: 0.85;
}

.games-error {
  text-align: center;
  padding: 2rem;
  color: #ef9a9a;
}

.games-error p {
  font-size: 1.1rem;
  margin-bottom: 1rem;
}

.no-games {
  text-align: center;
  padding: 3rem;
  color: #999;
}

.no-games p {
  font-size: 1.2rem;
  margin-bottom: 0.5rem;
}

.no-games .subtitle {
  font-size: 1rem;
  color: #777;
}

.v-card-actions {
  padding: 0.5rem 1rem;
}
</style>
