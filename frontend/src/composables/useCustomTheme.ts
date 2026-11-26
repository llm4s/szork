/**
 * The player's own theme description, and its validation by `/api/game/validate-theme`, which may hand
 * back a richer version of it to play.
 */

import { computed, ref } from "vue";
import axios from "axios";

/** The `/api/game/validate-theme` response. */
interface ThemeValidation {
  valid: boolean;
  message?: string;
  enhancedTheme?: string;
}

/** What was decided about a description: the theme to play it as, or why it will not do. */
interface Verdict {
  description: string;
  theme?: string;
  error?: string;
}

/** The shortest custom theme description worth validating. */
export const MIN_CUSTOM_THEME_LENGTH = 10;

export function useCustomTheme() {
  // What the player typed
  const description = ref("");
  const validating = ref(false);
  // The verdict on a description; it counts only while that is still the description (editing it starts afresh)
  const verdict = ref<Verdict | null>(null);
  const current = computed(() => (verdict.value?.description === description.value ? verdict.value : null));
  // The theme to play once validated (the validator's richer version, or the player's own); "" until then
  const validated = computed(() => current.value?.theme ?? "");
  // Why the description will not do, if it will not
  const error = computed(() => current.value?.error ?? "");
  // A validation that is still running
  let pending: Promise<void> | null = null;

  // Long enough to validate, and not refused. Not false while validating: the click that ended the editing (and so
  // started the validation) must still land, and `ensureValidated` waits for it
  const acceptable = computed(() => description.value.trim().length >= MIN_CUSTOM_THEME_LENGTH && !error.value);

  const runValidation = async () => {
    const theme = description.value;
    if (theme.trim().length < MIN_CUSTOM_THEME_LENGTH) {
      verdict.value = { description: theme, error: "Please provide a more detailed description" };
      return;
    }

    validating.value = true;
    try {
      const response = await axios.post<ThemeValidation>("/api/game/validate-theme", { theme });
      verdict.value = response.data.valid
        ? { description: theme, theme: response.data.enhancedTheme || theme }
        : {
            description: theme,
            error: response.data.message || "This theme may not work well for an adventure game",
          };
    } catch (failure) {
      console.error("Error validating theme:", failure);
      // The server could not judge it: the player goes ahead with their description
      verdict.value = { description: theme, theme };
    } finally {
      validating.value = false;
    }
  };

  /** Validate the description now (it lost focus). */
  const validate = (): Promise<void> => {
    const validation = runValidation().finally(() => {
      if (pending === validation) pending = null;
    });
    pending = validation;
    return validation;
  };

  /** Wait for the description to be validated (validating it if nobody has); whether it may be played. */
  const ensureValidated = async (): Promise<boolean> => {
    if (pending) await pending;
    if (!validated.value && !error.value) await validate();
    return !!validated.value;
  };

  /** Forget the verdict (another theme was chosen). */
  const reset = () => {
    verdict.value = null;
  };

  /** Resume with a description already validated (from a game that could not be started). */
  const restore = (theme: string) => {
    description.value = theme;
    verdict.value = { description: theme, theme };
  };

  return { description, error, validating, validated, acceptable, validate, ensureValidated, reset, restore };
}
