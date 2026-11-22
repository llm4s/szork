import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocketClient } from "./WebSocketClient";

/** A stand-in for the browser WebSocket that records what is sent and lets tests open or drop it. */
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.onclose?.();
  }

  /** The server accepts the connection. */
  open() {
    this.onopen?.();
  }

  /** The connection drops (not initiated by the client). */
  drop() {
    this.onclose?.();
  }

  sentTypes(): string[] {
    return this.sent.map((s) => JSON.parse(s).type);
  }
}

const latest = () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1];

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
