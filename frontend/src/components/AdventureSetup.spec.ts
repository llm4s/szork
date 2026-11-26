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
    vi.spyOn(console, "error").mockImplementation(() => {});
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

  it("keeps the adventure in the library when asked, and plays it from there", async () => {
    vi.mocked(axios.post).mockImplementation(async (url: string) =>
      url === "/api/adventures"
        ? { data: { status: "success", id: "the-lost-mine" } }
        : { data: { status: "success", outline } }
    );
    const wrapper = await mountSetup();
    await wrapper.findAll(".theme-card")[0].trigger("click");
    await wrapper.find(".continue-btn").trigger("click");
    await wrapper.findAll(".style-card")[1].trigger("click");
    await wrapper.find(".keep-adventure input").setValue(true);
    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();
    await vi.runAllTimersAsync();

    expect(axios.post).toHaveBeenCalledWith("/api/adventures", {
      outline,
      theme: expect.objectContaining({ id: "underground" }),
      artStyle: { id: "illustration", name: "Pencil Art" },
    });
    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config.adventureId).toBe("the-lost-mine");
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

  it("continues with a custom theme on the click that ends its editing", async () => {
    let finishValidation: (value: unknown) => void = () => {};
    vi.mocked(axios.post).mockImplementation(
      (url: string) =>
        new Promise((resolve) =>
          url === "/api/game/validate-theme"
            ? (finishValidation = resolve)
            : resolve({ data: { status: "success", outline } })
        )
    );
    const wrapper = await mountSetup();
    await wrapper.find(".custom-theme").trigger("click");
    const input = wrapper.find(".custom-theme-input input");
    await input.setValue("A steampunk airship exploring islands");
    await input.trigger("blur"); // starts the validation
    expect(wrapper.find(".continue-btn").attributes("disabled")).toBeUndefined();

    await wrapper.find(".continue-btn").trigger("click");
    finishValidation({ data: { valid: true } });
    await flushPromises();
    expect(wrapper.find(".style-card").exists()).toBe(true);
    expect(vi.mocked(axios.post).mock.calls.filter(([url]) => url === "/api/game/validate-theme")).toHaveLength(1);
  });

  it("says when the adventure cannot be designed, and lets the player try again", async () => {
    vi.mocked(axios.post).mockRejectedValueOnce(new Error("Network Error"));
    const wrapper = await mountSetup();
    await wrapper.findAll(".theme-card")[0].trigger("click");
    await wrapper.find(".continue-btn").trigger("click");
    await wrapper.findAll(".style-card")[1].trigger("click");
    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();

    expect(wrapper.find(".generation-failed").text()).toContain("Network Error");
    expect(wrapper.emitted("adventure-ready")).toBeUndefined();

    await wrapper.find(".retry-generation").trigger("click");
    await flushPromises();
    await vi.runAllTimersAsync();
    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config.outline).toEqual(outline);
  });

  it("lets the player play without a designed adventure when it cannot be designed", async () => {
    vi.mocked(axios.post).mockResolvedValue({ data: { status: "error", message: "The designer gave up" } });
    const wrapper = await mountSetup();
    await wrapper.findAll(".theme-card")[0].trigger("click");
    await wrapper.find(".continue-btn").trigger("click");
    await wrapper.findAll(".style-card")[1].trigger("click");
    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();
    expect(wrapper.find(".generation-failed").text()).toContain("The designer gave up");

    await wrapper.find(".play-without-outline").trigger("click");
    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config.outline).toBeNull();
  });

  it("resumes from a game that could not be started, without designing it again", async () => {
    const previous: AdventureConfig = {
      theme: { id: "underground", name: "Underground Realm", prompt: "Caves" },
      style: { id: "illustration", name: "Pencil Art" },
      outline,
      adventureId: "the-lost-mine",
      sessionFeatures: { image: false, music: false, tts: false, stt: true },
    };
    const wrapper = mountWithVuetify(AdventureSetup, { props: { previous } });
    await flushPromises();
    expect(wrapper.find(".style-card.selected").exists()).toBe(true);

    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();
    expect(axios.post).not.toHaveBeenCalled();
    const [[config]] = wrapper.emitted("adventure-ready") as [[AdventureConfig]];
    expect(config).toEqual({ ...previous, theme: expect.objectContaining({ id: "underground" }) });
  });

  it("goes back to game selection", async () => {
    const wrapper = await mountSetup();
    await wrapper.find(".action-buttons-sticky button").trigger("click");
    expect(wrapper.emitted("back-to-selection")).toHaveLength(1);
  });
});
