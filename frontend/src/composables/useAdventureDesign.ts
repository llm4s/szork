/**
 * Designing an adventure (`/api/game/generate-adventure`), with progress to show while the designer
 * works, and keeping a design in the adventure library (`/api/adventures`).
 */

import { ref } from "vue";
import axios from "axios";
import type { AdventureOutline, StyleChoice, ThemeChoice } from "@/types/api";

/** The `/api/game/generate-adventure` response. */
type GenerationResponse = { status: "success"; outline: AdventureOutline } | { status: "error"; message?: string };

/** The `/api/adventures` (keep an adventure) response. */
type KeepResponse = { status: "success"; id: string } | { status: "error"; message?: string };

/** What the progress line says while the designer works, in turn. */
const STATUS_MESSAGES = [
  "Creating your unique adventure...",
  "Designing exciting locations...",
  "Placing mysterious items...",
  "Bringing characters to life...",
  "Weaving the story together...",
  "Adding the finishing touches...",
];

export function useAdventureDesign() {
  const status = ref(STATUS_MESSAGES[0]);
  // The design, from when the designer answers (shown while the "ready" message is)
  const designed = ref<AdventureOutline | null>(null);
  // Percent; the designer gives no real progress, so this creeps towards 90 until it answers
  const progress = ref(0);

  /** Designs the adventure; throws when it cannot be designed. */
  const design = async (theme: ThemeChoice, style: StyleChoice): Promise<AdventureOutline> => {
    status.value = STATUS_MESSAGES[0];
    progress.value = 10;
    designed.value = null;
    let step = 0;
    const ticker = setInterval(() => {
      step = (step + 1) % STATUS_MESSAGES.length;
      status.value = STATUS_MESSAGES[step];
      progress.value = Math.min(progress.value + 15, 90);
    }, 2000);
    try {
      const response = await axios.post<GenerationResponse>("/api/game/generate-adventure", {
        theme,
        artStyle: style,
      });
      if (response.data.status !== "success") throw new Error(response.data.message || "the designer failed");
      clearInterval(ticker);
      designed.value = response.data.outline;
      status.value = "Adventure ready! Starting your journey...";
      progress.value = 100;
      // A moment to show it is done
      await new Promise((resolve) => setTimeout(resolve, 1500));
      return response.data.outline;
    } finally {
      clearInterval(ticker);
    }
  };

  /** Adds the outline to the adventure library; its id there, or undefined if it could not be kept. */
  const keep = async (outline: AdventureOutline, theme: ThemeChoice, style: StyleChoice) => {
    try {
      const response = await axios.post<KeepResponse>("/api/adventures", { outline, theme, artStyle: style });
      if (response.data.status === "success") return response.data.id;
      console.warn("The adventure was not kept:", response.data.message);
    } catch (error) {
      console.warn("The adventure was not kept:", error);
    }
    return undefined;
  };

  return { status, progress, designed, design, keep };
}
