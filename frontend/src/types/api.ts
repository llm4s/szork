/**
 * Types for the HTTP API (src/main/scala/org/llm4s/szork/api/SzorkServer.scala) and the adventure
 * choices passed between the setup screens and the game.
 */

/** An adventure design, as `AdventureGenerator.outlineToJson` writes it; sent back unchanged with newGame. */
export interface AdventureOutline {
  title: string;
  tagline: string | null;
  mainQuest: string;
  subQuests: string[];
  keyLocations: LocationOutline[];
  importantItems: ItemOutline[];
  keyCharacters: CharacterOutline[];
  adventureArc: string;
  specialMechanics: string | null;
}

export interface LocationOutline {
  id: string;
  name: string;
  description: string;
  significance: string;
}

export interface ItemOutline {
  name: string;
  description: string;
  purpose: string;
}

export interface CharacterOutline {
  name: string;
  role: string;
  description: string;
}

/** A server feature: `enabled` in its configuration, `available` when it also has the credentials it needs. */
export interface FeatureFlag {
  enabled: boolean;
  available: boolean;
}

/** The `/api/feature-flags` response. */
export interface FeatureFlags {
  llm: { provider: string; available: boolean };
  image: FeatureFlag & { provider: string };
  music: FeatureFlag;
  tts: FeatureFlag;
  stt: FeatureFlag;
}

/** A game in the `/api/games` response. */
export interface SavedGameSummary {
  gameId: string;
  title: string;
  theme: string;
  artStyle: string;
  createdAt: number;
  lastPlayed: number;
  totalPlayTime: number;
}

/** The theme the player chose, as sent to `/api/game/generate-adventure`. */
export interface ThemeChoice {
  id: string;
  name: string;
  prompt: string;
}

/** The art style the player chose. */
export interface StyleChoice {
  id: string;
  name: string;
}

/** The features the player asked for in this game. */
export interface SessionFeatures {
  image: boolean;
  music: boolean;
  tts: boolean;
  stt: boolean;
}

/** Everything the setup screens chose, handed to the game when the adventure is ready. */
export interface AdventureConfig {
  theme: ThemeChoice;
  style: StyleChoice;
  /** Null when generation failed: the game starts without an outline. */
  outline: AdventureOutline | null;
  sessionFeatures: SessionFeatures;
}
