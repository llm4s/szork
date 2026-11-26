// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import FeatureToggles from "./FeatureToggles.vue";
import { featureLabel, featureStatus } from "@/composables/useFeatureFlags";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import type { FeatureFlags, SessionFeatures } from "@/types/api";

const flags: FeatureFlags = {
  llm: { provider: "openai", available: true },
  image: { enabled: true, provider: "dalle", available: true },
  music: { enabled: true, available: false },
  tts: { enabled: false, available: false },
  stt: { enabled: true, available: true },
};

describe("featureStatus and featureLabel", () => {
  it("say whether a feature is on, missing what it needs, or switched off", () => {
    expect([flags.image, flags.music, flags.tts].map(featureStatus)).toEqual(["Enabled", "Unavailable", "Disabled"]);
    expect(featureLabel("Images", flags.image)).toBe("Images");
    expect(featureLabel("Music", flags.music)).toBe("Music (unavailable)");
    expect(featureLabel("TTS", flags.tts)).toBe("TTS (disabled)");
  });
});

describe("FeatureToggles", () => {
  const mountToggles = (features: SessionFeatures) =>
    mountWithVuetify(FeatureToggles, { props: { flags, features, keepAdventure: false } });

  it("labels each switch, and only lets the player switch the features the server offers", () => {
    const wrapper = mountToggles({ image: true, music: false, tts: false, stt: true });
    expect(wrapper.find(".feature-music").text()).toContain("Music (unavailable)");
    expect(wrapper.find(".feature-tts input").attributes("disabled")).toBeDefined();
    expect(wrapper.find(".feature-image input").attributes("disabled")).toBeUndefined();
  });

  it("hands back the features with the one switched changed", async () => {
    const wrapper = mountToggles({ image: true, music: false, tts: false, stt: true });
    await wrapper.find(".feature-image input").setValue(false);
    expect(wrapper.emitted("update:features")).toEqual([[{ image: false, music: false, tts: false, stt: true }]]);
    await wrapper.find(".keep-adventure input").setValue(true);
    expect(wrapper.emitted("update:keepAdventure")).toEqual([[true]]);
  });
});
