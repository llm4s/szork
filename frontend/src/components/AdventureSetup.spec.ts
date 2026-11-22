// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import axios from "axios";
import AdventureSetup from "./AdventureSetup.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import type { AdventureConfig, AdventureOutline, FeatureFlags } from "@/types/api";

vi.mock("axios");

const flags: FeatureFlags = {
  llm: { provider: "openai", available: true },
  image: { enabled: true, provider: "dalle", available: true },
  music: { enabled: true, available: false },
  tts: { enabled: false, available: false },
  stt: { enabled: true, available: true },
};

const outline: AdventureOutline = {
  title: "The Lost Mine",
  tagline: null,
  mainQuest: "Find the mine",
  subQuests: [],
  keyLocations: [],
  importantItems: [],
  keyCharacters: [],
  adventureArc: "",
  specialMechanics: null,
};

async function mountSetup() {
  const wrapper = mountWithVuetify(AdventureSetup);
  await flushPromises();
  return wrapper;
}

describe("AdventureSetup", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(axios.get).mockResolvedValue({ data: flags });
    vi.mocked(axios.post).mockResolvedValue({ data: { status: "success", outline } });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.resetAllMocks();
  });

  it("needs a theme before continuing", async () => {
    const wrapper = await mountSetup();
    const proceed = () => wrapper.find(".continue-btn");
    expect(proceed().attributes("disabled")).toBeDefined();
    await wrapper.findAll(".theme-card")[0].trigger("click");
    expect(proceed().attributes("disabled")).toBeUndefined();
  });

  it("generates the adventure and hands over the choices and the features the server offers", async () => {
    const wrapper = await mountSetup();
    await wrapper.findAll(".theme-card")[0].trigger("click");
    await wrapper.find(".continue-btn").trigger("click");
    await wrapper.findAll(".style-card")[1].trigger("click");
    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();
    await vi.runAllTimersAsync();

    expect(axios.post).toHaveBeenCalledWith("/api/game/generate-adventure", {
      theme: expect.objectContaining({ id: "underground", name: "Underground Realm" }),
      artStyle: { id: "illustration", name: "Pencil Art" },
    });
    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config.style).toEqual({ id: "illustration", name: "Pencil Art" });
    expect(config.outline).toEqual(outline);
    // Music and TTS are not available, so they start switched off
    expect(config.sessionFeatures).toEqual({ image: true, music: false, tts: false, stt: true });
  });

  it("validates a custom theme and uses the enhanced description", async () => {
    vi.mocked(axios.post).mockImplementation(async (url: string) =>
      url === "/api/game/validate-theme"
        ? { data: { valid: true, enhancedTheme: "An enhanced airship adventure" } }
        : { data: { status: "success", outline } }
    );
    const wrapper = await mountSetup();
    await wrapper.find(".custom-theme").trigger("click");
    await wrapper.find(".custom-theme-input input").setValue("A steampunk airship exploring islands");
    await wrapper.find(".continue-btn").trigger("click");
    await flushPromises();
    await wrapper.findAll(".style-card")[0].trigger("click");
    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();
    await vi.runAllTimersAsync();

    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config.theme).toEqual({ id: "custom", name: "Custom Adventure", prompt: "An enhanced airship adventure" });
  });

  it("goes back to game selection", async () => {
    const wrapper = await mountSetup();
    await wrapper.find(".action-buttons-sticky button").trigger("click");
    expect(wrapper.emitted("back-to-selection")).toHaveLength(1);
  });
});
