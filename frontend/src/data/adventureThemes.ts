/** The adventure themes and art styles offered when setting up a new game. */

export interface Theme {
  id: string;
  name: string;
  description: string;
  icon: string;
  /** What the adventure generator is told about the theme. */
  prompt: string;
}

export interface ArtStyle {
  id: string;
  name: string;
  description: string;
  /** Shown when there is no sample image. */
  gradient: string;
  sampleImage?: string;
}

/** The id of the player's own theme, described in free text. */
export const CUSTOM_THEME_ID = "custom";

export const PREDEFINED_THEMES: Theme[] = [
  {
    id: "underground",
    name: "Underground Realm",
    icon: "⛏️",
    description:
      "Cave systems, mines, and subterranean kingdoms filled with crystals, ancient ruins, and hidden treasures",
    prompt:
      "underground caverns with glowing crystals, ancient dwarven ruins, underground rivers, and mysterious tunnels",
  },
  {
    id: "ancient",
    name: "Ancient Mysteries",
    icon: "🏛️",
    description:
      "Pyramids, temples, enchanted forests, and mystical ruins with hieroglyphic puzzles, nature magic, and archaeological treasures",
    prompt: "ancient temples, mystical forests, magical ruins, hieroglyphic puzzles, and archaeological discoveries",
  },
  {
    id: "estate",
    name: "Abandoned Estate",
    icon: "🏰",
    description: "Mansions, castles, and towers with secret passages, mysterious artifacts, and family treasures",
    prompt: "gothic mansion with secret passages, mysterious artifacts, haunted rooms, and family secrets",
  },
  {
    id: "island",
    name: "Lost Island",
    icon: "🏝️",
    description: "Tropical or arctic expeditions featuring shipwrecks, pirate gold, and survival challenges",
    prompt: "mysterious island with shipwrecks, pirate treasures, jungle temples, and survival challenges",
  },
  {
    id: "space",
    name: "Space Frontier",
    icon: "🚀",
    description: "Space stations and alien worlds with technology puzzles, resource management, and cosmic mysteries",
    prompt: "space station orbiting alien worlds, advanced technology, cosmic mysteries, and alien encounters",
  },
  {
    id: "nautical",
    name: "Nautical Depths",
    icon: "🌊",
    description: "Underwater adventures in submarines or sunken cities, with pressure puzzles and oceanic treasures",
    prompt: "underwater exploration in sunken cities, submarine adventures, oceanic treasures, and deep sea mysteries",
  },
  {
    id: "noir",
    name: "Noir City",
    icon: "🕵️",
    description:
      "Rain-slicked streets, speakeasies, and office buildings hiding criminal treasures, coded messages, and conspiracy puzzles",
    prompt:
      "noir detective story in rain-slicked city streets, speakeasies, criminal underworld, and conspiracy mysteries",
  },
  {
    id: "cyberpunk",
    name: "Cyberpunk Metropolis",
    icon: "🌃",
    description:
      "Neon-lit corporate towers, digital vaults, and underground networks with hacking puzzles and data treasures",
    prompt: "cyberpunk metropolis with neon lights, corporate towers, digital networks, and hacking challenges",
  },
];

export const ART_STYLES: ArtStyle[] = [
  {
    id: "pixel",
    name: "Pixel Art",
    description: "Classic retro-style scenes with detailed pixelated environments, like 16-bit era adventure games",
    gradient: "linear-gradient(45deg, #8B4513, #228B22, #4169E1)",
    sampleImage: "/style-samples/pixel-sample.png",
  },
  {
    id: "illustration",
    name: "Pencil Art",
    description:
      "Professional pencil drawings with detailed shading, realistic textures, and fine graphite work - like an artist's sketchbook",
    gradient: "linear-gradient(45deg, #F5F5F5, #B8B8B8, #4A4A4A)",
    sampleImage: "/style-samples/illustration-sample.png",
  },
  {
    id: "painting",
    name: "Painting",
    description:
      "Fully rendered atmospheric scenes with realistic lighting, textures, and depth - like concept art or fantasy book covers",
    gradient: "linear-gradient(45deg, #4B0082, #800080, #FF69B4)",
    sampleImage: "/style-samples/painting-sample.png",
  },
  {
    id: "comic",
    name: "Comic/Graphic Novel",
    description:
      "Bold lines with cel-shaded coloring, dramatic perspectives, and stylized environments that pop off the screen",
    gradient: "linear-gradient(45deg, #FF0000, #FFD700, #000000)",
    sampleImage: "/style-samples/comic-sample.png",
  },
];
