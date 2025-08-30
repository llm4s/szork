<template>
  <div class="adventure-setup">
    <div class="setup-content">
      <transition name="fade" mode="out-in">
        <!-- Generating Adventure Screen -->
        <div v-if="currentStep === 'generating'" class="setup-step generating-adventure">
          <h2 class="setup-title">Crafting Your Adventure</h2>
          
          <div class="generation-container">
            <div class="loading-animation">
              <div class="spinner"></div>
              <div class="pulse-ring"></div>
            </div>
            
            <div class="generation-status">
              <p class="status-text">{{ generationStatus }}</p>
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: generationProgress + '%' }"></div>
              </div>
            </div>
            
            <div v-if="adventureOutline" class="adventure-preview">
              <h3>{{ adventureOutline.title }}</h3>
              <p class="quest-preview">{{ adventureOutline.mainQuest }}</p>
            </div>
          </div>
        </div>
        
        <!-- Theme Selection -->
        <div v-else-if="currentStep === 'theme'" class="setup-step">
          <div class="setup-header">
            <h2 class="setup-title">Choose Your Adventure Theme</h2>
          </div>
          
          <div class="setup-scrollable">
            <div class="theme-grid">
              <div 
                v-for="theme in predefinedThemes" 
                :key="theme.id"
                class="theme-card"
                :class="{ selected: selectedTheme === theme.id }"
                @click="selectTheme(theme.id)"
              >
                <div class="theme-icon">{{ theme.icon }}</div>
                <h3 class="theme-name">{{ theme.name }}</h3>
                <p class="theme-description">{{ theme.description }}</p>
              </div>
              
              <!-- Custom Theme Option -->
              <div 
                class="theme-card custom-theme"
                :class="{ selected: selectedTheme === 'custom' }"
                @click="selectTheme('custom')"
              >
                <div class="theme-icon">✨</div>
                <h3 class="theme-name">Custom Adventure</h3>
                <p class="theme-description">Create your own unique adventure setting</p>
              </div>
            </div>
            
            <!-- Custom Theme Input -->
            <div v-if="selectedTheme === 'custom'" class="custom-theme-input">
              <v-text-field
                v-model="customThemeInput"
                label="Describe your adventure theme"
                placeholder="e.g., A steampunk airship exploring floating islands..."
                variant="outlined"
                :error-messages="customThemeError"
                :loading="validatingCustomTheme"
                @blur="validateCustomTheme"
              />
            </div>
          </div>
          
          <div class="action-buttons-sticky">
            <v-btn
              variant="outlined"
              size="large"
              @click="backToSelection"
              class="mr-2"
            >
              <v-icon start>mdi-arrow-left</v-icon>
              Back
            </v-btn>
            <v-btn
              size="large"
              :disabled="!canProceedFromTheme"
              @click="proceedToStyle"
              class="continue-btn"
            >
              Continue to Art Style
              <v-icon end>mdi-chevron-right</v-icon>
            </v-btn>
          </div>
        </div>
        
        <!-- Art Style Selection -->
        <div v-else-if="currentStep === 'style'" class="setup-step">
          <div class="setup-header">
            <h2 class="setup-title">Choose Your Visual Style</h2>
          </div>
          
          <div class="setup-scrollable">
            <div class="style-grid">
              <div 
                v-for="style in artStyles" 
                :key="style.id"
                class="style-card"
                :class="{ selected: selectedStyle === style.id }"
                @click="selectStyle(style.id)"
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
          </div>
          
          <div class="action-buttons-sticky">
            <v-btn
              variant="outlined"
              size="large"
              @click="goBackToTheme"
              class="mr-2"
            >
              Back
            </v-btn>
            <v-tooltip
              :text="!serverAvailable ? 'Server not available - please ensure the game server is running' : ''"
              :disabled="serverAvailable"
              location="top"
            >
              <template v-slot:activator="{ props }">
                <v-btn
                  v-bind="props"
                  size="large"
                  :disabled="!selectedStyle || !serverAvailable || checkingServer"
                  @click="startAdventure"
                  :loading="startingGame || checkingServer"
                  class="begin-adventure-btn"
                  :color="!serverAvailable ? 'error' : 'primary'"
                >
                  <span v-if="!serverAvailable">Server Unavailable</span>
                  <span v-else-if="checkingServer">Checking Server...</span>
                  <span v-else>Begin Adventure</span>
                  <v-icon v-if="serverAvailable && !checkingServer" end>mdi-chevron-right</v-icon>
                  <v-icon v-else-if="!serverAvailable" end>mdi-alert-circle</v-icon>
                </v-btn>
              </template>
            </v-tooltip>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, computed, onMounted } from "vue";
import axios from "axios";
import { WebSocketClient } from "@/services/WebSocketClient";

interface Theme {
  id: string;
  name: string;
  description: string;
  icon: string;
  prompt?: string;
}

interface ArtStyle {
  id: string;
  name: string;
  description: string;
  gradient: string;
  sampleImage?: string;
}

export default defineComponent({
  name: "AdventureSetup",
  emits: ["adventure-ready", "back-to-selection"],
  setup(_, { emit }) {
    const currentStep = ref<"theme" | "style" | "generating">("theme");
    const selectedTheme = ref<string | null>(null);
    const selectedStyle = ref<string | null>(null);
    const customThemeInput = ref("");
    const customThemeError = ref("");
    const validatingCustomTheme = ref(false);
    const startingGame = ref(false);
    const validatedCustomTheme = ref("");
    const adventureOutline = ref<any>(null);
    const generationStatus = ref("Creating your unique adventure...");
    const generationProgress = ref(0);
    const serverAvailable = ref(true);
    const checkingServer = ref(false);
    
    const predefinedThemes: Theme[] = [
      {
        id: "underground",
        name: "Underground Realm",
        icon: "⛏️",
        description: "Cave systems, mines, and subterranean kingdoms filled with crystals, ancient ruins, and hidden treasures",
        prompt: "underground caverns with glowing crystals, ancient dwarven ruins, underground rivers, and mysterious tunnels"
      },
      {
        id: "ancient",
        name: "Ancient Mysteries",
        icon: "🏛️",
        description: "Pyramids, temples, enchanted forests, and mystical ruins with hieroglyphic puzzles, nature magic, and archaeological treasures",
        prompt: "ancient temples, mystical forests, magical ruins, hieroglyphic puzzles, and archaeological discoveries"
      },
      {
        id: "estate",
        name: "Abandoned Estate",
        icon: "🏰",
        description: "Mansions, castles, and towers with secret passages, mysterious artifacts, and family treasures",
        prompt: "gothic mansion with secret passages, mysterious artifacts, haunted rooms, and family secrets"
      },
      {
        id: "island",
        name: "Lost Island",
        icon: "🏝️",
        description: "Tropical or arctic expeditions featuring shipwrecks, pirate gold, and survival challenges",
        prompt: "mysterious island with shipwrecks, pirate treasures, jungle temples, and survival challenges"
      },
      {
        id: "space",
        name: "Space Frontier",
        icon: "🚀",
        description: "Space stations and alien worlds with technology puzzles, resource management, and cosmic mysteries",
        prompt: "space station orbiting alien worlds, advanced technology, cosmic mysteries, and alien encounters"
      },
      {
        id: "nautical",
        name: "Nautical Depths",
        icon: "🌊",
        description: "Underwater adventures in submarines or sunken cities, with pressure puzzles and oceanic treasures",
        prompt: "underwater exploration in sunken cities, submarine adventures, oceanic treasures, and deep sea mysteries"
      },
      {
        id: "noir",
        name: "Noir City",
        icon: "🕵️",
        description: "Rain-slicked streets, speakeasies, and office buildings hiding criminal treasures, coded messages, and conspiracy puzzles",
        prompt: "noir detective story in rain-slicked city streets, speakeasies, criminal underworld, and conspiracy mysteries"
      },
      {
        id: "cyberpunk",
        name: "Cyberpunk Metropolis",
        icon: "🌃",
        description: "Neon-lit corporate towers, digital vaults, and underground networks with hacking puzzles and data treasures",
        prompt: "cyberpunk metropolis with neon lights, corporate towers, digital networks, and hacking challenges"
      }
    ];
    
    const artStyles: ArtStyle[] = [
      {
        id: "pixel",
        name: "Pixel Art",
        description: "Classic retro-style scenes with detailed pixelated environments, like 16-bit era adventure games",
        gradient: "linear-gradient(45deg, #8B4513, #228B22, #4169E1)",
        sampleImage: "/style-samples/pixel-sample.png"
      },
      {
        id: "illustration",
        name: "Pencil Art",
        description: "Professional pencil drawings with detailed shading, realistic textures, and fine graphite work - like an artist's sketchbook",
        gradient: "linear-gradient(45deg, #F5F5F5, #B8B8B8, #4A4A4A)",
        sampleImage: "/style-samples/illustration-sample.png"
      },
      {
        id: "painting",
        name: "Painting",
        description: "Fully rendered atmospheric scenes with realistic lighting, textures, and depth - like concept art or fantasy book covers",
        gradient: "linear-gradient(45deg, #4B0082, #800080, #FF69B4)",
        sampleImage: "/style-samples/painting-sample.png"
      },
      {
        id: "comic",
        name: "Comic/Graphic Novel",
        description: "Bold lines with cel-shaded coloring, dramatic perspectives, and stylized environments that pop off the screen",
        gradient: "linear-gradient(45deg, #FF0000, #FFD700, #000000)",
        sampleImage: "/style-samples/comic-sample.png"
      }
    ];
    
    const canProceedFromTheme = computed(() => {
      if (selectedTheme.value === "custom") {
        return customThemeInput.value.trim().length > 10 && !customThemeError.value && !validatingCustomTheme.value;
      }
      return selectedTheme.value !== null;
    });
    
    const selectTheme = (themeId: string) => {
      selectedTheme.value = themeId;
      if (themeId !== "custom") {
        customThemeError.value = "";
        validatedCustomTheme.value = "";
      }
    };
    
    const selectStyle = (styleId: string) => {
      selectedStyle.value = styleId;
    };
    
    const validateCustomTheme = async () => {
      if (customThemeInput.value.trim().length < 10) {
        customThemeError.value = "Please provide a more detailed description";
        return;
      }
      
      validatingCustomTheme.value = true;
      customThemeError.value = "";
      
      try {
        const response = await axios.post("/api/game/validate-theme", {
          theme: customThemeInput.value
        });
        
        if (response.data.valid) {
          validatedCustomTheme.value = response.data.enhancedTheme || customThemeInput.value;
          customThemeError.value = "";
        } else {
          customThemeError.value = response.data.message || "This theme may not work well for an adventure game";
        }
      } catch (error) {
        console.error("Error validating theme:", error);
        // Allow the theme anyway if validation fails
        validatedCustomTheme.value = customThemeInput.value;
      } finally {
        validatingCustomTheme.value = false;
      }
    };
    
    const proceedToStyle = () => {
      if (selectedTheme.value === "custom" && !validatedCustomTheme.value) {
        validateCustomTheme().then(() => {
          if (!customThemeError.value) {
            currentStep.value = "style";
          }
        });
      } else {
        currentStep.value = "style";
      }
    };
    
    const goBackToTheme = () => {
      currentStep.value = "theme";
    };
    
    const backToSelection = () => {
      emit("back-to-selection");
    };
    
    const generateAdventureOutline = async (themeData: any, styleData: any) => {
      try {
        // Update status messages during generation
        const statusMessages = [
          "Creating your unique adventure...",
          "Designing exciting locations...",
          "Placing mysterious items...",
          "Bringing characters to life...",
          "Weaving the story together...",
          "Adding the finishing touches..."
        ];
        
        let messageIndex = 0;
        const statusInterval = setInterval(() => {
          messageIndex = (messageIndex + 1) % statusMessages.length;
          generationStatus.value = statusMessages[messageIndex];
          generationProgress.value = Math.min(generationProgress.value + 15, 90);
        }, 2000);
        
        const response = await axios.post("/api/game/generate-adventure", {
          theme: themeData,
          artStyle: styleData
        });
        
        clearInterval(statusInterval);
        
        if (response.data.status === "success") {
          adventureOutline.value = response.data.outline;
          generationStatus.value = "Adventure ready! Starting your journey...";
          generationProgress.value = 100;
          
          // Wait a moment to show completion
          await new Promise(resolve => setTimeout(resolve, 1500));
          
          return response.data.outline;
        } else {
          throw new Error(response.data.message || "Failed to generate adventure");
        }
      } catch (error) {
        console.error("Error generating adventure:", error);
        generationStatus.value = "Using default adventure...";
        generationProgress.value = 100;
        await new Promise(resolve => setTimeout(resolve, 1000));
        return null; // Will proceed without outline
      }
    };
    
    // Check server availability on mount
    onMounted(async () => {
      checkingServer.value = true;
      const wsClient = new WebSocketClient();
      try {
        serverAvailable.value = await wsClient.checkServerAvailability(3000);
        if (!serverAvailable.value) {
          console.warn('[AdventureSetup] Server is not available');
        }
      } catch (error) {
        console.error('[AdventureSetup] Error checking server:', error);
        serverAvailable.value = false;
      } finally {
        checkingServer.value = false;
        wsClient.disconnect();
      }
    });
    
    const startAdventure = async () => {
      if (!selectedTheme.value || !selectedStyle.value) return;
      
      // Check server availability before starting
      if (!serverAvailable.value) {
        console.error('[AdventureSetup] Cannot start adventure - server not available');
        return;
      }
      
      startingGame.value = true;
      currentStep.value = "generating";
      generationProgress.value = 10;
      
      // Get the theme details
      let themeData;
      if (selectedTheme.value === "custom") {
        themeData = {
          id: "custom",
          name: "Custom Adventure",
          prompt: validatedCustomTheme.value || customThemeInput.value
        };
      } else {
        const theme = predefinedThemes.find(t => t.id === selectedTheme.value);
        themeData = {
          id: theme?.id,
          name: theme?.name,
          prompt: theme?.prompt
        };
      }
      
      // Get the style details
      const style = artStyles.find(s => s.id === selectedStyle.value);
      const styleData = {
        id: style?.id,
        name: style?.name
      };
      
      // Generate the adventure outline
      const outline = await generateAdventureOutline(themeData, styleData);
      
      // Emit the ready event with the outline
      emit("adventure-ready", {
        theme: themeData,
        style: styleData,
        outline: outline
      });
    };
    
    return {
      currentStep,
      selectedTheme,
      selectedStyle,
      customThemeInput,
      customThemeError,
      validatingCustomTheme,
      startingGame,
      predefinedThemes,
      artStyles,
      canProceedFromTheme,
      selectTheme,
      selectStyle,
      validateCustomTheme,
      proceedToStyle,
      goBackToTheme,
      backToSelection,
      startAdventure,
      adventureOutline,
      generationStatus,
      generationProgress,
      serverAvailable,
      checkingServer
    };
  }
});
</script>

<style scoped>
.adventure-setup {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #0d0d0d 0%, #1a1a2e 100%);
  overflow: hidden;
  position: relative;
}

.setup-content {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.setup-step {
  animation: fadeIn 0.5s ease-in;
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}

.setup-header {
  flex-shrink: 0;
  padding: 1.5rem 2rem 1rem;
}

.setup-scrollable {
  flex: 1;
  overflow-y: auto;
  padding: 0 2rem;
  padding-bottom: 120px; /* Space for sticky buttons */
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}

.setup-title {
  text-align: center;
  font-size: 2.5rem;
  margin: 0;
  margin-bottom: 1rem;
  color: #fff;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.5);
}

.theme-grid, .style-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1.5rem;
  margin-bottom: 2rem;
}

.theme-card, .style-card {
  background: rgba(255, 255, 255, 0.05);
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 1.5rem;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.theme-card:hover, .style-card:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.theme-card.selected, .style-card.selected {
  background: rgba(33, 150, 243, 0.2);
  border-color: #2196F3;
  box-shadow: 0 0 20px rgba(33, 150, 243, 0.3);
}

.theme-icon {
  font-size: 3rem;
  text-align: center;
  margin-bottom: 1rem;
}

.theme-name, .style-name {
  font-size: 1.3rem;
  margin-bottom: 0.5rem;
  color: #fff;
}

.theme-description, .style-description {
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

.custom-theme-input {
  max-width: 600px;
  margin: 2rem auto;
}

.action-buttons {
  display: flex;
  justify-content: center;
  gap: 1rem;
  margin-top: 3rem;
}

.action-buttons-sticky {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 1rem;
  padding: 1.5rem;
  background: linear-gradient(to top, rgba(13, 13, 13, 0.95), rgba(13, 13, 13, 0.85));
  backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  z-index: 100;
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.5);
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
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

/* Responsive Design */
@media (max-width: 1024px) {
  .theme-grid, .style-grid {
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 1rem;
  }
}

@media (max-width: 768px) {
  .setup-header {
    padding: 1rem 1rem 0.5rem;
  }
  
  .setup-scrollable {
    padding: 0 1rem;
    padding-bottom: 100px;
  }
  
  .theme-grid, .style-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 0.75rem;
  }
  
  .setup-title {
    font-size: 1.8rem;
    margin-bottom: 0.75rem;
  }
  
  .theme-card, .style-card {
    padding: 1rem;
  }
  
  .theme-icon {
    font-size: 2rem;
    margin-bottom: 0.5rem;
  }
  
  .theme-name, .style-name {
    font-size: 1.1rem;
  }
  
  .theme-description, .style-description {
    font-size: 0.85rem;
  }
  
  .action-buttons-sticky {
    padding: 1rem;
  }
  
  .continue-btn, .begin-adventure-btn {
    font-size: 0.9rem;
    padding: 0 16px !important;
  }
}

@media (max-width: 480px) {
  .theme-grid, .style-grid {
    grid-template-columns: 1fr;
  }
  
  .theme-card, .style-card {
    padding: 1.25rem;
  }
}

.begin-adventure-btn, .continue-btn {
  background-color: #000000 !important;
  color: #ffffff !important;
  border: 2px solid #ffffff !important;
  border-radius: 8px !important;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.5px;
  padding: 0 24px !important;
  box-shadow: 
    inset 2px 2px 4px rgba(255, 255, 255, 0.2),
    inset -2px -2px 4px rgba(0, 0, 0, 0.5),
    0 4px 8px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
}

.begin-adventure-btn:hover:not(:disabled), .continue-btn:hover:not(:disabled) {
  background-color: #1a1a1a !important;
  border-color: #ffffff !important;
  box-shadow: 
    inset 2px 2px 6px rgba(255, 255, 255, 0.3),
    inset -2px -2px 6px rgba(0, 0, 0, 0.6),
    0 6px 12px rgba(0, 0, 0, 0.4);
  transform: translateY(-2px);
}

.begin-adventure-btn:active:not(:disabled), .continue-btn:active:not(:disabled) {
  box-shadow: 
    inset 2px 2px 4px rgba(0, 0, 0, 0.6),
    inset -2px -2px 4px rgba(255, 255, 255, 0.1),
    0 2px 4px rgba(0, 0, 0, 0.2);
  transform: translateY(0);
}

.begin-adventure-btn:disabled, .continue-btn:disabled {
  opacity: 0.5;
  background-color: #333333 !important;
  border-color: #666666 !important;
}

.generating-adventure {
  text-align: center;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 2rem;
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
  border-top: 3px solid #2196F3;
  border-radius: 50%;
  animation: spin 1.5s linear infinite;
}

.pulse-ring {
  position: absolute;
  width: 100%;
  height: 100%;
  border: 2px solid #2196F3;
  border-radius: 50%;
  animation: pulse 2s ease-out infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
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
  0%, 100% { opacity: 0.7; }
  50% { opacity: 1; }
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
  background: linear-gradient(90deg, #2196F3, #00BCD4);
  border-radius: 4px;
  transition: width 0.5s ease;
  box-shadow: 0 0 10px rgba(33, 150, 243, 0.5);
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
  color: #2196F3;
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

/* Custom Scrollbar Styling */
.setup-scrollable::-webkit-scrollbar {
  width: 8px;
}

.setup-scrollable::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 4px;
}

.setup-scrollable::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
}

.setup-scrollable::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* Ensure custom theme input is always visible when selected */
.custom-theme-input {
  position: relative;
  z-index: 10;
}
</style>