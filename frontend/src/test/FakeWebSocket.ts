/** A stand-in for the browser WebSocket that records what is sent and lets tests open, drop or answer it. */
export class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }

  /** The most recently created socket. */
  static latest(): FakeWebSocket {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
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

  /** The server sends a message in the `{type, data}` wire format. */
  receive(type: string, data: object) {
    this.onmessage?.({ data: JSON.stringify({ type, data }) });
  }

  sentMessages(): Array<Record<string, unknown> & { type: string }> {
    return this.sent.map((s) => JSON.parse(s));
  }

  sentTypes(): string[] {
    return this.sentMessages().map((m) => m.type);
  }
}
