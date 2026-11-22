import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocketClient } from "./WebSocketClient";
import { FakeWebSocket } from "@/test/FakeWebSocket";

const latest = () => FakeWebSocket.latest();

describe("WebSocketClient", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("does not reconnect after disconnect()", async () => {
    const client = new WebSocketClient("ws://test");
    const connected = client.connect();
    latest().open();
    await connected;

    client.disconnect();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(client.connected).toBe(false);
  });

  it("reconnects after a dropped connection, resuming before queued messages are sent", async () => {
    const client = new WebSocketClient("ws://test");
    const changes: Array<[boolean, boolean]> = [];
    client.onConnectionChange((connected, reconnected) => {
      changes.push([connected, reconnected]);
      if (connected && reconnected) client.resumeSession("sess-1");
    });
    const connected = client.connect();
    latest().open();
    await connected;

    latest().drop();
    client.sendStreamCommand("look"); // queued while offline
    await vi.advanceTimersByTimeAsync(1_000);
    expect(FakeWebSocket.instances).toHaveLength(2);
    latest().open();

    expect(changes).toEqual([
      [true, false],
      [false, false],
      [true, true],
    ]);
    expect(latest().sentTypes()).toEqual(["resumeSession", "streamCommand"]);
  });

  it("rejects connect() if the socket closes before opening", async () => {
    const client = new WebSocketClient("ws://test");
    const connected = client.connect();
    latest().drop();
    await expect(connected).rejects.toThrow("closed before it opened");
    // A connection that never opened is not retried
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});
