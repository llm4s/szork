// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import axios from "axios";
import GameSelection from "./GameSelection.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import type { FeatureFlags, SavedGameSummary } from "@/types/api";

vi.mock("axios");

const flags: FeatureFlags = {
  llm: { provider: "anthropic", available: true },
  image: { enabled: true, provider: "dalle", available: true },
  music: { enabled: false, available: false },
  tts: { enabled: true, available: false },
  stt: { enabled: true, available: true },
};

const game = (gameId: string, title: string): SavedGameSummary => ({
  gameId,
  title,
  theme: "Underground Realm",
  artStyle: "pixel",
  createdAt: Date.now(),
  lastPlayed: Date.now(),
  totalPlayTime: 0,
});

async function mountSelection(games: SavedGameSummary[]) {
  vi.mocked(axios.get).mockImplementation(async (url: string) =>
    url === "/api/games" ? { data: { status: "success", games } } : { data: flags }
  );
  const wrapper = mountWithVuetify(GameSelection);
  await flushPromises();
  return wrapper;
}

describe("GameSelection", () => {
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.resetAllMocks();
    vi.restoreAllMocks();
  });

  it("lists saved games by title", async () => {
    const wrapper = await mountSelection([game("g1", "The Lost Mine"), game("g2", "")]);
    expect(wrapper.findAll(".game-card .game-title").map((t) => t.text())).toEqual([
      "The Lost Mine",
      "Untitled Adventure",
    ]);
  });

  it("shows the server's capabilities", async () => {
    const wrapper = await mountSelection([]);
    const text = wrapper.find(".capabilities").text();
    expect(text).toContain("LLM: anthropic");
    expect(text).toContain("Images: dalle");
    expect(text).toContain("Music: Disabled");
    expect(text).toContain("TTS: Unavailable");
    expect(text).toContain("STT: Enabled");
  });

  it("asks to load a game from the card or its Continue button", async () => {
    const wrapper = await mountSelection([game("g1", "The Lost Mine")]);
    await wrapper.find(".game-card").trigger("click");
    await wrapper.find(".game-card .v-card-actions button").trigger("click");
    expect(wrapper.emitted("load-game")).toEqual([["g1"], ["g1"]]);
  });

  it("asks to start a new game", async () => {
    const wrapper = await mountSelection([]);
    await wrapper.find(".new-game-button").trigger("click");
    expect(wrapper.emitted("start-new-game")).toHaveLength(1);
    expect(wrapper.find(".no-games").exists()).toBe(true);
  });
});
