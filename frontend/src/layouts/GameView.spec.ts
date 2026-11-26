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

  it("shows the loaded game's title, and nothing of the previous game while the next one loads", async () => {
    const { wrapper } = await loadedGame();
    latest().receive("gameLoaded", {
      ...flags,
      sessionId: "sess-1",
      gameId: "g1",
      title: "The Lost Mine",
      messageIndex: 1,
      conversation: [{ role: "assistant", content: "You are in a cave." }],
    });
    await flushPromises();
    expect(wrapper.find(".game-title").text()).toContain("SZork - The Lost Mine");

    // The browser's back or forward button opens another game in the same view
    await wrapper.setProps({ gameId: "g2" });
    await flushPromises();
    expect(latest().sentMessages().at(-1)).toMatchObject({ type: "loadGame", gameId: "g2" });
    expect(wrapper.text()).not.toContain("You are in a cave.");
    expect(wrapper.find(".game-title").text()).not.toContain("The Lost Mine");
    expect(wrapper.text()).toContain("Loading your adventure...");
  });

  it("starts a custom adventure with the player's description as its prompt, listed by the theme's name", async () => {
    const { wrapper } = await openView();
    const description = "A steampunk airship exploring floating islands";
    (wrapper.vm as unknown as { onAdventureReady: (c: AdventureConfig) => void }).onAdventureReady({
      theme: { id: "custom", name: "Custom Adventure", prompt: description },
      style: { id: "illustration", name: "Pencil Art" },
      outline,
      sessionFeatures: { image: true, music: false, tts: false, stt: false },
    });
    await flushPromises();
    expect(latest().sentMessages()[0]).toMatchObject({
      type: "newGame",
      theme: description,
      themeName: "Custom Adventure",
    });
    expect(wrapper.find(".game-title").text()).toContain("The Lost Mine");
  });

  it("returns to the game list, saying so, when the game being played is deleted", async () => {
    const { wrapper, router } = await loadedGame();
    latest().receive("gameDeleted", { gameId: "g1" });
    await vi.advanceTimersByTimeAsync(10); // the navigation home

    expect(router.currentRoute.value.path).toBe("/");
    expect(wrapper.find(".game-selection-screen").exists()).toBe(true);
    expect((wrapper.vm as unknown as { errorMessage: string }).errorMessage).toContain("deleted");
  });

  it("puts a command the server never received back in the command line", async () => {
    const { wrapper } = await loadedGame();
    const input = wrapper.find(".game-input input");
    await input.setValue("go north");
    await input.trigger("keyup.enter");
    expect((input.element as HTMLInputElement).value).toBe("");

    latest().drop();
    await vi.advanceTimersByTimeAsync(1_000);
    latest().open();
    latest().receive("sessionResumed", { sessionId: "sess-1", gameId: "g1", messageIndex: 1, turnInProgress: false });
    await flushPromises();
    expect((input.element as HTMLInputElement).value).toBe("go north");
  });

  it("stops the command at the server's limit, and counts only near it", async () => {
    const { wrapper } = await loadedGame();
    const input = wrapper.find(".game-input input");
    expect(input.attributes("maxlength")).toBe("1000");
    expect(wrapper.find(".game-input .v-counter").exists()).toBe(false);
    await input.setValue("x".repeat(900));
    expect(wrapper.find(".game-input .v-counter").text()).toContain("900 / 1000");
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
