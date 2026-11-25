// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import GameHeaderControls from "./GameHeaderControls.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";

function mountControls(available = true) {
  return mountWithVuetify(GameHeaderControls, {
    props: {
      imageAvailable: available,
      musicAvailable: available,
      ttsAvailable: available,
      imageGeneration: true,
      backgroundMusic: true,
      narration: false,
    },
  });
}

describe("GameHeaderControls", () => {
  it("toggles each feature through its v-model", async () => {
    const wrapper = mountControls();
    await wrapper.find(".image-toggle").trigger("click");
    await wrapper.find(".music-toggle").trigger("click");
    await wrapper.find(".narration-toggle").trigger("click");
    expect(wrapper.emitted("update:imageGeneration")).toEqual([[false]]);
    expect(wrapper.emitted("update:backgroundMusic")).toEqual([[false]]);
    expect(wrapper.emitted("update:narration")).toEqual([[true]]);
  });

  it("shows each feature's state in its icon", () => {
    const wrapper = mountControls();
    expect(wrapper.find(".image-toggle").html()).toContain("mdi-image");
    expect(wrapper.find(".narration-toggle").html()).toContain("mdi-account-voice-off");
  });

  it("disables the switches for features the server does not provide", async () => {
    const wrapper = mountControls(false);
    for (const selector of [".image-toggle", ".music-toggle", ".narration-toggle"]) {
      expect(wrapper.find(selector).attributes("disabled")).toBeDefined();
      await wrapper.find(selector).trigger("click");
    }
    expect(wrapper.emitted("update:imageGeneration")).toBeUndefined();
    expect(wrapper.emitted("update:backgroundMusic")).toBeUndefined();
    expect(wrapper.emitted("update:narration")).toBeUndefined();
  });

  it("names each button and says which features are on", () => {
    const wrapper = mountControls();
    expect(wrapper.find(".back-btn").attributes("aria-label")).toBe("Back to adventure selection");
    expect(wrapper.find(".image-toggle").attributes("aria-pressed")).toBe("true");
    expect(wrapper.find(".narration-toggle").attributes("aria-label")).toBe("Voice narration");
    expect(wrapper.find(".narration-toggle").attributes("aria-pressed")).toBe("false");
  });

  it("emits back from the back button", async () => {
    const wrapper = mountControls();
    await wrapper.find(".back-btn").trigger("click");
    expect(wrapper.emitted("back")).toHaveLength(1);
  });
});
