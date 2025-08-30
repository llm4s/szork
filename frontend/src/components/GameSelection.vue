<template>
  <div class="game-selection-screen">
    <div class="selection-content">
      <h1 class="selection-title">SZork Adventures</h1>
      
      <v-btn 
        size="x-large" 
        class="new-game-button"
        @click="$emit('start-new-game')"
      >
        <v-icon start>mdi-plus-circle</v-icon>
        Start New Adventure
      </v-btn>
      
      <div v-if="loading" class="loading-container">
        <v-progress-circular
          indeterminate
          color="primary"
          size="40"
          width="3"
        ></v-progress-circular>
      </div>
      
      <div v-else-if="savedGames.length > 0" class="saved-games-section">
        <h2 class="section-title">Continue Your Adventures</h2>
        <div class="games-grid">
          <v-card 
            v-for="game in savedGames" 
            :key="game.gameId"
            class="game-card"
            @click="selectGame(game)"
            hover
          >
            <v-card-title class="game-title">
              {{ game.title }}
            </v-card-title>
            <v-card-subtitle class="game-theme">
              {{ game.theme }} • {{ game.artStyle }}
            </v-card-subtitle>
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
              <v-btn 
                variant="text" 
                color="primary"
                @click.stop="loadGame(game.gameId)"
              >
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
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted } from 'vue';
import axios from 'axios';
import { useRouter } from 'vue-router';

interface SavedGame {
  gameId: string;
  title: string;
  theme: string;
  artStyle: string;
  createdAt: number;
  lastPlayed: number;
  totalPlayTime: number;
}

export default defineComponent({
  name: 'GameSelection',
  emits: ['start-new-game', 'load-game'],
  setup(props, { emit }) {
    const router = useRouter();
    const savedGames = ref<SavedGame[]>([]);
    const loading = ref(true);
    const deleteDialog = ref(false);
    const gameToDelete = ref<SavedGame | null>(null);
    
    const loadSavedGames = async () => {
      try {
        loading.value = true;
        const response = await axios.get('/api/games');
        if (response.data.status === 'success') {
          savedGames.value = response.data.games;
        }
      } catch (error) {
        console.error('Error loading saved games:', error);
      } finally {
        loading.value = false;
      }
    };
    
    const formatDate = (timestamp: number) => {
      return new Date(timestamp).toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric'
      });
    };
    
    const formatRelativeDate = (timestamp: number) => {
      const now = Date.now();
      const diff = now - timestamp;
      const minutes = Math.floor(diff / 60000);
      const hours = Math.floor(diff / 3600000);
      const days = Math.floor(diff / 86400000);
      
      if (minutes < 1) return 'Just now';
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
    
    const selectGame = (game: SavedGame) => {
      emit('load-game', game.gameId);
    };
    
    const loadGame = (gameId: string) => {
      router.push(`/game/${gameId}`);
    };
    
    const confirmDelete = (game: SavedGame) => {
      gameToDelete.value = game;
      deleteDialog.value = true;
    };
    
    const deleteGame = async () => {
      if (!gameToDelete.value) return;
      
      try {
        // Call the backend to delete the game
        await axios.delete(`/api/game/${gameToDelete.value.gameId}`);
        
        // Remove from the frontend list
        savedGames.value = savedGames.value.filter(g => g.gameId !== gameToDelete.value!.gameId);
        deleteDialog.value = false;
        gameToDelete.value = null;
      } catch (error) {
        console.error('Error deleting game:', error);
        alert('Failed to delete the game. Please try again.');
      }
    };
    
    onMounted(() => {
      loadSavedGames();
    });
    
    return {
      savedGames,
      loading,
      deleteDialog,
      gameToDelete,
      formatDate,
      formatRelativeDate,
      formatPlayTime,
      selectGame,
      loadGame,
      confirmDelete,
      deleteGame
    };
  }
});
</script>

<style scoped>
.game-selection-screen {
  width: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  padding: 2rem;
}

.selection-content {
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