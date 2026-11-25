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

  it("asks for a new game's features, with images off when the player turned them off", async () => {
    const game = useWebSocketGame();
    const starting = game.startNewGame("caves", "pixel", false, null, { tts: true, music: false });
    latest().open();
    await starting;
    expect(latest().sentMessages()).toEqual([
      {
        type: "newGame",
        theme: "caves",
        artStyle: "pixel",
        adventureOutline: null,
        tts: true,
        music: false,
        image: false,
      },
    ]);
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

  it("waits for the response to a voice command and sends the image toggle", async () => {
    const game = await startedGame();
    await game.sendAudioCommand("YXVkaW8=", false);
    expect(game.awaitingResponse.value).toBe(true);
    expect(latest().sentMessages()).toContainEqual({ type: "audioCommand", audio: "YXVkaW8=", imageGeneration: false });

    latest().receive("transcription", { text: "look" });
    expect(game.awaitingResponse.value).toBe(true);
    latest().receive("commandResponse", {
      text: "A new room.",
      messageIndex: 4,
      command: "look",
      hasImage: false,
      hasMusic: false,
    });
    expect(game.awaitingResponse.value).toBe(false);

    const response = game.messages.value[game.messages.value.length - 1];
    expect(response.text).toBe("A new room.");
    expect(response.imageLoading).toBe(false);
  });

  it("asks for no image with a voice command when the session has images off", async () => {
    const game = await startedGame();
    game.imageEnabled.value = false;
    await game.sendAudioCommand("YXVkaW8=");
    expect(latest().sentMessages()).toContainEqual({ type: "audioCommand", audio: "YXVkaW8=", imageGeneration: false });
  });

  it("streams a response into one message, replaced by the final text", async () => {
    const game = await startedGame();
    await game.sendCommand("go north");
    expect(latest().sentMessages()).toContainEqual({
      type: "streamCommand",
      command: "go north",
      imageGeneration: true,
    });

    latest().receive("textChunk", { text: "You go ", chunkNumber: 1 });
    latest().receive("textChunk", { text: "nor", chunkNumber: 2 });
    const streaming = game.messages.value[game.messages.value.length - 1];
    expect(streaming.text).toBe("You go nor");
    expect(streaming.isStreaming).toBe(true);

    latest().receive("streamComplete", {
      text: "You go north.",
      messageIndex: 4,
      totalChunks: 2,
      duration: 10,
      hasImage: true,
      hasMusic: false,
    });
    const response = game.messages.value[game.messages.value.length - 1];
    expect(game.messages.value.map((m) => m.text)).toEqual(["You are in a cave.", "go north", "You go north."]);
    expect(response.isStreaming).toBe(false);
    expect(response.messageIndex).toBe(4);
    expect(response.imageLoading).toBe(true);
    expect(game.isStreaming.value).toBe(false);
  });

  it("attaches media to the response with the same message index", async () => {
    const game = await startedGame();
    latest().receive("commandResponse", {
      text: "Room A",
      messageIndex: 4,
      command: "a",
      hasImage: true,
      hasMusic: false,
    });
    latest().receive("commandResponse", {
      text: "Room B",
      messageIndex: 6,
      command: "b",
      hasImage: true,
      hasMusic: false,
    });
    const [, roomA, roomB] = game.messages.value;

    // Out of order: the later response's image arrives first, the earlier one's fails
    latest().receive("imageReady", { messageIndex: 6, image: "Yg==" });
    latest().receive("mediaFailed", { messageIndex: 4, kind: "image" });

    expect(roomB.image).toBe("data:image/png;base64,Yg==");
    expect(roomB.imageLoading).toBe(false);
    expect(roomA.image).toBeUndefined();
    expect(roomA.imageLoading).toBe(false);
  });

  it("plays music and narration through the callbacks", async () => {
    const game = useWebSocketGame();
    const music = vi.fn();
    const narration = vi.fn();
    game.setPlayBackgroundMusicCallback(music);
    game.setPlayAudioNarrationCallback(narration);
    const connecting = game.connect();
    latest().open();
    await connecting;

    latest().receive("commandResponse", {
      text: "Hello",
      messageIndex: 2,
      command: "hi",
      audio: "c3BlZWNo",
      hasImage: false,
      hasMusic: true,
    });
    latest().receive("musicReady", { messageIndex: 2, music: "bXVzaWM=", mood: "calm" });

    expect(narration).toHaveBeenCalledWith("c3BlZWNo");
    expect(music).toHaveBeenCalledWith("data:audio/mpeg;base64,bXVzaWM=", "calm");
  });

  it("shows and plays the adventure library's media from its URLs", async () => {
    const game = useWebSocketGame();
    const music = vi.fn();
    game.setPlayBackgroundMusicCallback(music);
    const connecting = game.connect();
    latest().open();
    await connecting;

    latest().receive("commandResponse", {
      text: "A hall",
      messageIndex: 2,
      command: "go north",
      hasImage: true,
      hasMusic: true,
    });
    latest().receive("imageReady", { messageIndex: 2, url: "/api/library/adventures/a/images/hall.pixel.jpg?v=1" });
    latest().receive("musicReady", { messageIndex: 2, mood: "mystery", url: "/api/library/music/mystery.mp3?v=1" });

    expect(game.messages.value.at(-1)?.image).toBe("/api/library/adventures/a/images/hall.pixel.jpg?v=1");
    expect(music).toHaveBeenCalledWith("/api/library/music/mystery.mp3?v=1", "mystery");
  });

  it("starts a library adventure by its id, leaving the outline to the server", async () => {
    const game = useWebSocketGame();
    const starting = game.startNewGame(undefined, undefined, true, null, { tts: false }, "the-lost-mine");
    latest().open();
    await starting;
    expect(latest().sentMessages()).toEqual([
      { type: "newGame", adventureId: "the-lost-mine", tts: false, image: true },
    ]);
  });

  it("reports a game that cannot be loaded through loadFailed, not as a message", async () => {
    const game = useWebSocketGame();
    const loading = game.loadGame("missing");
    latest().open();
    await loading;
    latest().receive("error", { error: "Game not found", details: "missing" });

    expect(game.loadFailed.value).toBe("Game not found: missing");
    expect(game.messages.value).toEqual([]);
    expect(game.errorSnackbar.value).toBe(true);

    // A later error is shown in the game, not as a failed load
    game.loadFailed.value = null;
    latest().receive("error", { error: "Command failed" });
    expect(game.loadFailed.value).toBeNull();
    expect(game.messages.value[0].text).toBe("Error: Command failed");
  });

  it("shows a loaded game's history and current scene", async () => {
    const game = useWebSocketGame();
    const loading = game.loadGame("game-1");
    latest().open();
    await loading;
    const scene = { locationName: "Cave", exits: [], items: [], npcs: [] };
    latest().receive("gameLoaded", {
      ...flags,
      sessionId: "sess-2",
      gameId: "game-1",
      conversation: [
        { role: "assistant", content: "You are in a cave." },
        { role: "user", content: "look" },
        { role: "assistant", content: "It is dark." },
      ],
      messageIndex: 3,
      currentScene: scene,
    });

    expect(game.messages.value.map((m) => [m.isUser, m.text])).toEqual([
      [false, "You are in a cave."],
      [true, "look"],
      [false, "It is dark."],
    ]);
    expect(game.messages.value[2].scene).toEqual(scene);
    expect(game.sessionId.value).toBe("sess-2");
    expect(game.loadFailed.value).toBeNull();
  });

  it("reloads when the response to the first command after loading was lost", async () => {
    const game = useWebSocketGame();
    const loading = game.loadGame("game-1");
    latest().open();
    await loading;
    latest().receive("gameLoaded", {
      ...flags,
      sessionId: "sess-2",
      gameId: "game-1",
      messageIndex: 3,
      conversation: [{ role: "assistant", content: "You are in a cave." }],
    });
    await game.sendCommand("go north");

    latest().drop(); // streamComplete is sent now, and lost
    await reconnect();
    expect(latest().sentMessages()[0]).toEqual({ type: "resumeSession", sessionId: "sess-2" });
    latest().receive("sessionResumed", { sessionId: "sess-2", gameId: "game-1", messageIndex: 5 });
    await vi.advanceTimersByTimeAsync(0);

    expect(game.isStreaming.value).toBe(false);
    expect(latest().sentTypes()).toContain("loadGame");
  });

  it("records how the adventure ended, and forgets it with the game", async () => {
    const game = await startedGame();
    expect(game.gameOutcome.value).toBeNull();

    latest().receive("gameEnded", { messageIndex: 4, outcome: "completed" });
    expect(game.gameOutcome.value).toBe("completed");

    game.clearMessages();
    expect(game.gameOutcome.value).toBeNull();
  });

  it("stops playing and resuming a session another tab took over, until the player takes it back", async () => {
    const game = await startedGame();
    await game.sendCommand("go north");
    latest().receive("sessionTakenOver", { sessionId: "sess-1", gameId: "game-1" });

    expect(game.sessionTakenOver.value).toBe(true);
    expect(game.isStreaming.value).toBe(false);
    expect(game.gameId.value).toBe("game-1");

    // Commands are not sent, and a reconnection does not take the session back
    const sentBefore = latest().sent.length;
    await game.sendCommand("look");
    await game.sendAudioCommand("YXVkaW8=");
    expect(latest().sent).toHaveLength(sentBefore);
    latest().drop();
    await reconnect();
    await vi.advanceTimersByTimeAsync(0);
    expect(latest().sentTypes()).not.toContain("resumeSession");

    // Taking it back loads the game again, with the features it had
    game.takeBackSession();
    await vi.advanceTimersByTimeAsync(0);
    expect(game.sessionTakenOver.value).toBe(false);
    expect(latest().sentMessages()).toContainEqual({
      type: "loadGame",
      gameId: "game-1",
      tts: false,
      stt: true,
      music: false,
      image: true,
    });
    latest().receive("gameLoaded", {
      ...flags,
      sessionId: "sess-1",
      gameId: "game-1",
      messageIndex: 4,
      conversation: [],
    });
    expect(game.sessionTakenOver.value).toBe(false);
    expect(game.sessionId.value).toBe("sess-1");
  });

  it("ignores a takeover notice for a session it no longer plays", async () => {
    const game = await startedGame();
    latest().receive("sessionTakenOver", { sessionId: "sess-old", gameId: "game-0" });
    expect(game.sessionTakenOver.value).toBe(false);
    expect(game.sessionId.value).toBe("sess-1");
  });
});
