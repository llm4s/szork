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
  exits: ExitOutline[];
  interactables: string[];
  puzzles: string | null;
  secrets: string | null;
}

/** An exit the designer planned: `state` is open, closed, locked, sealed or hidden. */
export interface ExitOutline {
  direction: string;
  targetId: string;
  description: string;
  state: string;
  obstacle: string | null;
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

/** The `/api/games` response. */
export type GamesResponse = { status: "success"; games: SavedGameSummary[] } | { status: "error"; message?: string };

/** The `DELETE /api/game/:gameId` response. */
export type DeleteGameResponse = { status: "success"; message: string } | { status: "error"; error: string };

/** The `/api/adventures` response. */
export type AdventuresResponse = { status: "success"; adventures: AdventureSummary[] } | { status: "error" };

/** A ready-made adventure in the `/api/adventures` response. */
export interface AdventureSummary {
  id: string;
  title: string;
  tagline: string | null;
  mainQuest: string;
  theme: string | null;
  artStyle: string | null;
  locations: number;
  /** How many of its locations already have a picture in its art style. */
  images: number;
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
  /** Null when the player chose to play without a designed adventure (after its design failed). */
  outline: AdventureOutline | null;
  /** The library adventure to play, when the outline was kept in (or came from) the adventure library. */
  adventureId?: string;
  sessionFeatures: SessionFeatures;
}
