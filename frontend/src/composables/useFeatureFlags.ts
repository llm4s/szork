/**
 * The game server's features, from `/api/feature-flags`. An answer also shows the server is up.
 */

import { computed, ref } from "vue";
import axios from "axios";
import type { FeatureFlag, FeatureFlags } from "@/types/api";

/** The features a player can switch on or off for a game. */
export type SessionFeature = "image" | "music" | "tts" | "stt";

/** Where a feature stands: switched off in the server's configuration, configured but missing what it needs, or on. */
export type FeatureStatus = "Enabled" | "Unavailable" | "Disabled";

/** The status of a feature, from its flag. */
export function featureStatus(flag: FeatureFlag): FeatureStatus {
  if (!flag.enabled) return "Disabled";
  return flag.available ? "Enabled" : "Unavailable";
}

/** A feature's switch label: its name, and why it cannot be switched on if it cannot ("Music (unavailable)"). */
export function featureLabel(name: string, flag: FeatureFlag): string {
  const status = featureStatus(flag);
  return status === "Enabled" ? name : `${name} (${status.toLowerCase()})`;
}

export function useFeatureFlags() {
  // Null until the server answers: until then (or if it never does) no feature is offered
  const flags = ref<FeatureFlags | null>(null);
  const loading = ref(false);
  // The server did not answer
  const failed = ref(false);

  const load = async () => {
    loading.value = true;
    try {
      const response = await axios.get<FeatureFlags>("/api/feature-flags", { timeout: 3000 });
      flags.value = response.data;
      failed.value = false;
    } catch (error) {
      console.warn("Unable to fetch the server's features:", error);
      failed.value = true;
    } finally {
      loading.value = false;
    }
  };

  /** Whether the server is configured for a feature and has what it needs to provide it. */
  const offered = (feature: SessionFeature): boolean => {
    const flag: FeatureFlag | undefined = flags.value?.[feature];
    return !!flag && flag.enabled && flag.available;
  };

  return { flags, loaded: computed(() => flags.value !== null), loading, failed, load, offered };
}
