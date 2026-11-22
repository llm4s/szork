/**
 * Plays the game's spoken narration and looping background music, each of which the player can switch
 * off. Audio arrives as base64 MP3 from the server.
 */

import { ref, watch, onUnmounted, getCurrentInstance } from "vue";

// Narration over quieter background music
export const NARRATION_VOLUME = 0.8;
export const MUSIC_VOLUME = 0.3;

type Log = (message: string, ...args: unknown[]) => void;

export interface AudioPlaybackOptions {
  log?: Log;
}

export function useAudioPlayback(options: AudioPlaybackOptions = {}) {
  const log: Log = options.log ?? (() => {});
  const narrationEnabled = ref(true);
  const backgroundMusicEnabled = ref(true);
  let narration: HTMLAudioElement | null = null;
  let music: HTMLAudioElement | null = null;

  /** Speak a response, replacing any narration still playing. Does nothing while narration is off. */
  const playNarration = (audioBase64: string) => {
    if (!narrationEnabled.value) {
      log("Narration is disabled, skipping playback");
      return;
    }
    try {
      if (narration && !narration.paused) {
        narration.pause();
        narration = null;
      }
      const audio = new Audio(`data:audio/mpeg;base64,${audioBase64}`);
      audio.volume = NARRATION_VOLUME;
      narration = audio;
      audio.addEventListener("error", () => log("Narration audio error:", audio.error));
      audio.addEventListener("ended", () => {
        if (narration === audio) narration = null;
      });
      audio.play().catch((error) => log("Error playing audio narration:", error));
    } catch (error) {
      log("Error creating narration audio element:", error);
    }
  };

  /** Loop a music track, replacing the current one. Does nothing while music is off. */
  const playBackgroundMusic = (musicBase64: string, mood: string) => {
    log(`Playing background music, mood: ${mood}, enabled: ${backgroundMusicEnabled.value}`);
    if (!backgroundMusicEnabled.value) return;
    try {
      stopBackgroundMusic();
      const track = new Audio(`data:audio/mpeg;base64,${musicBase64}`);
      track.volume = MUSIC_VOLUME;
      track.loop = true;
      music = track;
      track.play().catch((error) => {
        log("Error playing background music:", error);
        if (music === track) music = null;
      });
    } catch (error) {
      log("Error creating background music element:", error);
      music = null;
    }
  };

  const stopBackgroundMusic = () => {
    if (music) {
      music.pause();
      music.src = "";
      music = null;
    }
  };

  const stopNarration = () => {
    if (narration) {
      narration.pause();
      narration.src = "";
      narration = null;
    }
  };

  /** Stop and release all audio (e.g. when leaving the game). */
  const stopAll = () => {
    stopBackgroundMusic();
    stopNarration();
  };

  // Switching music off pauses the track; switching it back on resumes it
  watch(backgroundMusicEnabled, (enabled) => {
    if (!music) return;
    if (!enabled && !music.paused) {
      music.pause();
    } else if (enabled && music.paused) {
      music.play().catch((error) => log("Error resuming background music:", error));
    }
  });

  // Switching narration off stops the current narration
  watch(narrationEnabled, (enabled) => {
    if (!enabled && narration && !narration.paused) {
      narration.pause();
      narration.currentTime = 0;
      narration = null;
    }
  });

  // The composable can also be used outside a component (e.g. in tests)
  if (getCurrentInstance()) onUnmounted(stopAll);

  return {
    narrationEnabled,
    backgroundMusicEnabled,
    playNarration,
    playBackgroundMusic,
    stopBackgroundMusic,
    stopAll,
  };
}
