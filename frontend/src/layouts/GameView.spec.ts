// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import axios from "axios";
import GameView from "./GameView.vue";
import AdventureSetup from "@/components/AdventureSetup.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import { FakeWebSocket } from "@/test/FakeWebSocket";
import type { AdventureConfig, AdventureOutline } from "@/types/api";

vi.mock("axios");

const latest = () => FakeWebSocket.latest();

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

const flags = { ttsEnabled: false, sttEnabled: true, imageEnabled: true, musicEnabled: false };

/** The game view opened on the game page of `gameId`, or on the home page; its connection is open. */
async function openView(gameId?: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: GameView },
      { path: "/game/:gameId", component: GameView, props: true },
    ],
  });
  await router.push(gameId ? `/game/${gameId}` : "/");
  const wrapper = mountWithVuetify(GameView, { props: { gameId }, global: { plugins: [router] } });
  latest().open();
  await flushPromises();
  return { wrapper, router };
}

/** The game view opened on a saved game's link, with the game loaded. */
async function loadedGame() {
  const { wrapper, router } = await openView("g1");
  expect(latest().sentTypes()).toEqual(["loadGame"]);
  latest().receive("gameLoaded", {
    ...flags,
    sessionId: "sess-1",
    gameId: "g1",
    messageIndex: 1,
    conversation: [{ role: "assistant", content: "You are in a cave." }],
  });
  await flushPromises();
  return { wrapper, router };
}

/** The connection drops and every attempt to reconnect fails. */
async function serverGoesAway() {
  latest().drop();
  for (let attempt = 1; attempt <= 5; attempt++) {
    await vi.advanceTimersByTimeAsync(1_000 * attempt);
    latest().drop();
  }
  await flushPromises();
}

describe("GameView", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    // Overlays (the error snackbar) position themselves by the visual viewport, which jsdom does not have
    vi.stubGlobal("visualViewport", new EventTarget());
    vi.spyOn(console, "log").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "warn").mockImplementation(() => {});
    // The game list finds nothing, and the server offers no features
    vi.mocked(axios.get).mockImplementation(async (url: string) =>
      url === "/api/feature-flags"
        ? Promise.reject(new Error("offline"))
        : { data: { status: "success", games: [], adventures: [] } }
    );
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.resetAllMocks();
  });

  it("says when it is reconnecting, and offers to retry once it has given up", async () => {
    const { wrapper } = await loadedGame();
    expect(wrapper.find(".connection-alert").exists()).toBe(false);

    latest().drop();
    await flushPromises();
    expect(wrapper.find(".connection-alert").text()).toContain("Reconnecting");

    await serverGoesAway();
    expect(wrapper.find(".connection-alert").text()).toContain("Not connected");

    await wrapper.find(".retry-connection").trigger("click");
    latest().open();
    await flushPromises();
    expect(wrapper.find(".connection-alert").exists()).toBe(false);
    expect(latest().sentMessages()).toEqual([{ type: "resumeSession", sessionId: "sess-1" }]);
  });

  it("gives a command that could not be sent back to the player, and says why", async () => {
    const { wrapper } = await loadedGame();
    await serverGoesAway();

    const input = wrapper.find(".game-input input");
    await input.setValue("go north");
    await input.trigger("keyup.enter");
    expect((input.element as HTMLInputElement).value).toBe("");
    latest().drop(); // the server is still down
    await flushPromises();

    expect((input.element as HTMLInputElement).value).toBe("go north");
    const vm = wrapper.vm as unknown as { errorMessage: string; errorSnackbar: boolean };
    expect(vm.errorSnackbar).toBe(true);
    expect(vm.errorMessage).toContain("Could not reach the game server");
    expect(wrapper.text()).not.toContain("> go north");
  });

  it("returns to a designed adventure's setup, keeping its outline, when the game cannot be started", async () => {
    const { wrapper } = await openView();
    const config: AdventureConfig = {
      theme: { id: "underground", name: "Underground Realm", prompt: "Caves" },
      style: { id: "illustration", name: "Pencil Art" },
      outline: { ...outline },
      sessionFeatures: { image: true, music: false, tts: false, stt: false },
    };
    (wrapper.vm as unknown as { onAdventureReady: (c: AdventureConfig) => void }).onAdventureReady(config);
    await flushPromises();
    expect(latest().sentTypes()).toEqual(["newGame"]);

    latest().receive("error", { error: "Failed to start game", details: "The model is unavailable" });
    await flushPromises();
    const setup = wrapper.findComponent(AdventureSetup);
    expect(setup.exists()).toBe(true);
    expect(setup.props("previous")).toEqual(config);
    expect(wrapper.find(".style-card.selected").exists()).toBe(true);
  });

  it("returns to the game list when a game cannot be loaded", async () => {
    const { wrapper, router } = await openView("gone");
    latest().receive("error", { error: "Failed to load game", details: "Game not found" });
    await vi.advanceTimersByTimeAsync(10); // the navigation home

    expect(router.currentRoute.value.path).toBe("/");
    expect(wrapper.find(".game-selection-screen").exists()).toBe(true);
    expect((wrapper.vm as unknown as { errorMessage: string }).errorMessage).toBe(
      "Failed to load game: Game not found"
    );
  });
});
