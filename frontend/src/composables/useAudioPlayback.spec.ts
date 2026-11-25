import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import { MUSIC_VOLUME, NARRATION_VOLUME, useAudioPlayback } from "./useAudioPlayback";

/** A stand-in for HTMLAudioElement that records what the composable does with it. */
class FakeAudio {
  static instances: FakeAudio[] = [];
  static failPlay = false;
  volume = 1;
  loop = false;
  paused = true;
  currentTime = 0;
  error = null;
  private listeners = new Map<string, Array<() => void>>();

  constructor(public src: string) {
    FakeAudio.instances.push(this);
  }

  play(): Promise<void> {
    if (FakeAudio.failPlay) return Promise.reject(new Error("autoplay blocked"));
    this.paused = false;
    return Promise.resolve();
  }

  pause() {
    this.paused = true;
  }

  addEventListener(type: string, listener: () => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  emit(type: string) {
    this.listeners.get(type)?.forEach((l) => l());
  }
}

describe("useAudioPlayback", () => {
  beforeEach(() => {
    FakeAudio.instances = [];
    FakeAudio.failPlay = false;
    vi.stubGlobal("Audio", FakeAudio);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("plays narration at the narration volume", () => {
    const audio = useAudioPlayback();
    audio.playNarration("bmFycmF0aW9u");
    const [narration] = FakeAudio.instances;
    expect(narration.src).toBe("data:audio/mpeg;base64,bmFycmF0aW9u");
    expect(narration.volume).toBe(NARRATION_VOLUME);
    expect(narration.paused).toBe(false);
  });

  it("replaces narration that is still playing", () => {
    const audio = useAudioPlayback();
    audio.playNarration("Zmlyc3Q=");
    audio.playNarration("c2Vjb25k");
    const [first, second] = FakeAudio.instances;
    expect(first.paused).toBe(true);
    expect(second.paused).toBe(false);
  });

  it("does not play narration while narration is off", () => {
    const audio = useAudioPlayback();
    audio.narrationEnabled.value = false;
    audio.playNarration("bmFycmF0aW9u");
    expect(FakeAudio.instances).toHaveLength(0);
  });

  it("stops narration when narration is switched off", async () => {
    const audio = useAudioPlayback();
    audio.playNarration("bmFycmF0aW9u");
    const [narration] = FakeAudio.instances;
    audio.narrationEnabled.value = false;
    await nextTick();
    expect(narration.paused).toBe(true);
    expect(narration.currentTime).toBe(0);
  });

  it("loops music at the music volume and replaces the previous track", () => {
    const audio = useAudioPlayback();
    audio.playBackgroundMusic("data:audio/mpeg;base64,Zmlyc3Q=", "calm");
    audio.playBackgroundMusic("data:audio/mpeg;base64,c2Vjb25k", "tense");
    const [first, second] = FakeAudio.instances;
    expect(first.paused).toBe(true);
    expect(first.src).toBe("");
    expect(second.loop).toBe(true);
    expect(second.volume).toBe(MUSIC_VOLUME);
    expect(second.paused).toBe(false);
  });

  it("does not play music while music is off", () => {
    const audio = useAudioPlayback();
    audio.backgroundMusicEnabled.value = false;
    audio.playBackgroundMusic("data:audio/mpeg;base64,bXVzaWM=", "calm");
    expect(FakeAudio.instances).toHaveLength(0);
  });

  it("pauses music when switched off and resumes it when switched back on", async () => {
    const audio = useAudioPlayback();
    audio.playBackgroundMusic("data:audio/mpeg;base64,bXVzaWM=", "calm");
    const [music] = FakeAudio.instances;
    audio.backgroundMusicEnabled.value = false;
    await nextTick();
    expect(music.paused).toBe(true);
    audio.backgroundMusicEnabled.value = true;
    await nextTick();
    expect(music.paused).toBe(false);
  });

  it("forgets a track the browser refused to play", async () => {
    FakeAudio.failPlay = true;
    const audio = useAudioPlayback();
    audio.playBackgroundMusic("data:audio/mpeg;base64,bXVzaWM=", "calm");
    await Promise.resolve();
    await Promise.resolve();
    const [music] = FakeAudio.instances;
    audio.stopBackgroundMusic();
    // Already forgotten, so stopping does not touch it
    expect(music.src).toBe("data:audio/mpeg;base64,bXVzaWM=");
  });

  it("stopAll stops and releases narration and music", () => {
    const audio = useAudioPlayback();
    audio.playNarration("bmFycmF0aW9u");
    audio.playBackgroundMusic("data:audio/mpeg;base64,bXVzaWM=", "calm");
    audio.stopAll();
    for (const element of FakeAudio.instances) {
      expect(element.paused).toBe(true);
      expect(element.src).toBe("");
    }
  });
});
