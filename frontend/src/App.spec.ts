// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import axios from "axios";
import App from "./App.vue";
import router from "@/router";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import { FakeWebSocket } from "@/test/FakeWebSocket";
import type { FeatureFlags } from "@/types/api";

vi.mock("axios");

const flags = { ttsEnabled: false, sttEnabled: true, imageEnabled: true, musicEnabled: false };

const featureFlags: FeatureFlags = {
  llm: { provider: "anthropic", available: true },
  image: { enabled: true, provider: "dalle", available: true },
  music: { enabled: false, available: false },
  tts: { enabled: true, available: false },
  stt: { enabled: true, available: true },
};

/** Smoke tests: the whole app (router, layout and Vuetify components) mounts and renders each screen. */
describe("App", () => {
  let warn: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    warn = vi.spyOn(console, "warn");
    FakeWebSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.spyOn(console, "log").mockImplementation(() => {});
    vi.mocked(axios.get).mockImplementation(async (url: string) =>
      url === "/api/games" ? { data: { status: "success", games: [] } } : { data: featureFlags }
    );
  });

  afterEach(() => {
    // Vue and Vuetify report runtime problems (unknown props, failed resolution) as warnings
    expect(warn).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  async function mountAt(path: string) {
    await router.push(path);
    await router.isReady();
    const wrapper = mountWithVuetify(App, { global: { plugins: [router] }, attachTo: document.body });
    await flushPromises();
    FakeWebSocket.latest().open();
    await flushPromises();
    return wrapper;
  }

  it("shows the intro screen, then the game selection", async () => {
    const wrapper = await mountAt("/");
    expect(wrapper.find(".intro-screen").exists()).toBe(true);

    await wrapper.find(".begin-adventure-btn").trigger("click");
    await flushPromises();

    expect(wrapper.find(".intro-screen").exists()).toBe(false);
    expect(wrapper.text()).toContain("No saved adventures found");
    wrapper.unmount();
  });

  it("shows the game screen for a saved game in the URL", async () => {
    const wrapper = await mountAt("/game/game-1");
    FakeWebSocket.latest().receive("gameLoaded", {
      ...flags,
      sessionId: "sess-1",
      gameId: "game-1",
      conversation: [{ role: "assistant", content: "You are in a cave." }],
      messageIndex: 1,
    });
    await flushPromises();

    expect(wrapper.find(".game-title").exists()).toBe(true);
    expect(wrapper.find("input").attributes("placeholder")).toBe("Enter your command...");
    expect(wrapper.text()).toContain("You are in a cave.");
    wrapper.unmount();
  });
});
