import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocketClient, type ConnectionState } from "./WebSocketClient";
import { FakeWebSocket } from "@/test/FakeWebSocket";

const latest = () => FakeWebSocket.latest();

/** A client that records its connection states. */
function recordingClient() {
  const client = new WebSocketClient("ws://test");
  const states: ConnectionState[] = [];
  client.onConnectionChange((state) => states.push(state));
  return { client, states };
}

describe("WebSocketClient", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWebSocket);
    vi.spyOn(console, "log").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("does not reconnect after disconnect()", async () => {
    const { client, states } = recordingClient();
    const connected = client.connect();
    latest().open();
    await connected;

    client.disconnect();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(states).toEqual(["connecting", "open", "closed"]);
  });

  it("reconnects after a dropped connection, resuming before queued messages are sent", async () => {
    const client = new WebSocketClient("ws://test");
    const changes: Array<[ConnectionState, boolean]> = [];
    client.onConnectionChange((state, reconnected) => {
      changes.push([state, reconnected]);
      if (state === "open" && reconnected) client.resumeSession("sess-1");
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
      ["connecting", false],
      ["open", false],
      ["reconnecting", false],
      ["open", true],
    ]);
    expect(latest().sentTypes()).toEqual(["resumeSession", "streamCommand"]);
  });

  it("rejects connect() if the socket closes before opening", async () => {
    const { client, states } = recordingClient();
    const connected = client.connect();
    latest().drop();
    await expect(connected).rejects.toThrow("closed before it opened");
    // A connection that never opened is not retried
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(states).toEqual(["connecting", "closed"]);
  });

  it("waits for the connection being opened rather than reporting it open", async () => {
    const client = new WebSocketClient("ws://test");
    const first = client.connect();
    let secondSettled = false;
    const second = client.connect().then(() => (secondSettled = true));
    await vi.advanceTimersByTimeAsync(0);
    expect(secondSettled).toBe(false);
    expect(FakeWebSocket.instances).toHaveLength(1);

    latest().open();
    await Promise.all([first, second]);
    expect(secondSettled).toBe(true);
  });

  it("gives up after five failed attempts, dropping what was queued", async () => {
    const { client, states } = recordingClient();
    const connected = client.connect();
    latest().open();
    await connected;

    latest().drop();
    client.sendStreamCommand("look"); // queued while offline
    for (let attempt = 1; attempt <= 5; attempt++) {
      await vi.advanceTimersByTimeAsync(1_000 * attempt);
      latest().drop(); // the server is still down
    }
    expect(states.at(-1)).toBe("closed");

    // A later connection does not send the command the client gave up on
    const reconnecting = client.connect();
    latest().open();
    await reconnecting;
    expect(latest().sent).toEqual([]);
  });

  it("ignores the close of a socket it has already replaced", async () => {
    const { client, states } = recordingClient();
    const connected = client.connect();
    const old = latest();
    old.open();
    await connected;

    client.disconnect();
    const reconnecting = client.connect();
    latest().open();
    await reconnecting;

    old.drop(); // a late close event from the first socket
    await vi.advanceTimersByTimeAsync(60_000);
    expect(states.at(-1)).toBe("open");
    expect(FakeWebSocket.instances).toHaveLength(2);
  });
});
