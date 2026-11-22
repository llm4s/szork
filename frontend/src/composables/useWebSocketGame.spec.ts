import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useWebSocketGame } from "./useWebSocketGame";
import { FakeWebSocket } from "@/test/FakeWebSocket";
import { SESSION_EXPIRED } from "@/types/WebSocketProtocol";

const latest = () => FakeWebSocket.latest();

const flags = { ttsEnabled: false, sttEnabled: true, imageEnabled: true, musicEnabled: false };

/** A connected game whose opening response had message index 2. */
async function startedGame() {
  const game = useWebSocketGame();
  const connecting = game.connect();
  latest().open();
  await connecting;
  latest().receive("gameStarted", {
    ...flags,
    sessionId: "sess-1",
    gameId: "game-1",
    text: "You are in a cave.",
    messageIndex: 2,
    hasImage: false,
    hasMusic: false,
  });
  return game;
}

/** Let the client's reconnection timer fire and the new socket open. */
async function reconnect() {
  await vi.advanceTimersByTimeAsync(1_000);
  latest().open();
}

describe("useWebSocketGame", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.spyOn(console, "log").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("resumes the session when a command is sent while waiting to reconnect", async () => {
    const game = await startedGame();
    latest().drop();

    // Before the reconnection timer fires: the same client reconnects now
    const sending = game.sendCommand("look");
    expect(FakeWebSocket.instances).toHaveLength(2);
    latest().open();
    await sending;

    expect(latest().sentMessages().slice(0, 2)).toEqual([
      { type: "resumeSession", sessionId: "sess-1" },
      { type: "streamCommand", command: "look", imageGeneration: true },
    ]);
    // The superseded reconnection timer does not open another socket
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeWebSocket.instances).toHaveLength(2);
  });

  it("resumes the session after the reconnection attempts run out", async () => {
    const game = await startedGame();
    latest().drop();
    for (let attempt = 1; attempt <= 5; attempt++) {
      await vi.advanceTimersByTimeAsync(1_000 * attempt);
      latest().drop(); // the server is still down
    }
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeWebSocket.instances).toHaveLength(6);
    expect(game.isConnected.value).toBe(false);

    const sending = game.sendCommand("look");
    latest().open();
    await sending;

    expect(latest().sentTypes().slice(0, 2)).toEqual(["resumeSession", "streamCommand"]);
    expect(game.isConnected.value).toBe(true);
  });

  it("resumes after an automatic reconnection", async () => {
    await startedGame();
    latest().drop();
    await reconnect();
    expect(latest().sentMessages()).toEqual([{ type: "resumeSession", sessionId: "sess-1" }]);
  });

  it("reloads the game when the streamed response was sent while disconnected", async () => {
    const game = await startedGame();
    await game.sendCommand("go north");
    expect(game.isStreaming.value).toBe(true);

    latest().drop(); // streamComplete is sent now, and lost
    await reconnect();
    latest().receive("sessionResumed", { sessionId: "sess-1", gameId: "game-1", messageIndex: 4 });
    await vi.advanceTimersByTimeAsync(0); // the reload is sent asynchronously

    expect(game.isStreaming.value).toBe(false);
    expect(game.awaitingResponse.value).toBe(false);
    expect(latest().sentMessages()).toContainEqual({
      type: "loadGame",
      gameId: "game-1",
      tts: false,
      stt: true,
      music: false,
      image: true,
    });
  });

  it("keeps waiting when the server has not finished the turn", async () => {
    const game = await startedGame();
    await game.sendCommand("go north");

    latest().drop();
    await reconnect();
    latest().receive("sessionResumed", { sessionId: "sess-1", gameId: "game-1", messageIndex: 2 });
    await vi.advanceTimersByTimeAsync(0);

    expect(game.isStreaming.value).toBe(true);
    expect(latest().sentTypes()).not.toContain("loadGame");

    latest().receive("streamComplete", {
      text: "You go north.",
      messageIndex: 4,
      totalChunks: 1,
      duration: 10,
      hasImage: false,
      hasMusic: false,
    });
    expect(game.isStreaming.value).toBe(false);
    expect(game.messages.value[game.messages.value.length - 1].text).toBe("You go north.");
  });

  it("reloads an expired session with the features it had", async () => {
    await startedGame();
    latest().receive("error", { error: "Session expired", details: SESSION_EXPIRED });
    await vi.advanceTimersByTimeAsync(0); // the reload is sent asynchronously

    expect(latest().sentMessages()).toContainEqual({
      type: "loadGame",
      gameId: "game-1",
      tts: false,
      stt: true,
      music: false,
      image: true,
    });
  });

  it("waits for the response to a voice command and honours the image toggle", async () => {
    const game = await startedGame();
    await game.sendAudioCommand("YXVkaW8=", false);
    expect(game.awaitingResponse.value).toBe(true);

    latest().receive("transcription", { text: "look" });
    expect(game.awaitingResponse.value).toBe(true);
    latest().receive("commandResponse", {
      text: "A new room.",
      messageIndex: 4,
      command: "look",
      hasImage: true,
      hasMusic: false,
    });
    expect(game.awaitingResponse.value).toBe(false);

    const response = game.messages.value[game.messages.value.length - 1];
    expect(response.hasImage).toBe(false);
    expect(response.imageLoading).toBe(false);
    latest().receive("imageReady", { messageIndex: 4, image: "aW1n" });
    expect(response.image).toBeUndefined();
  });
});
