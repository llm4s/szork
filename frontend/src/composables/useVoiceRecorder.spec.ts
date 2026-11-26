// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { blobToBase64, MIN_RECORDING_MS, useVoiceRecorder } from "./useVoiceRecorder";

class FakeTrack {
  stopped = false;
  stop() {
    this.stopped = true;
  }
}

class FakeStream {
  tracks = [new FakeTrack()];
  getTracks() {
    return this.tracks;
  }
  get released() {
    return this.tracks.every((t) => t.stopped);
  }
}

/** A stand-in for MediaRecorder: stopping it delivers `FakeRecorder.data` and then fires onstop. */
class FakeRecorder {
  static instances: FakeRecorder[] = [];
  static supported = "audio/webm";
  static data = ["sound"];
  mimeType: string;
  started = false;
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;

  constructor(
    public stream: FakeStream,
    options: { mimeType?: string }
  ) {
    this.mimeType = options.mimeType ?? "";
    FakeRecorder.instances.push(this);
  }

  static isTypeSupported(format: string) {
    return format === FakeRecorder.supported;
  }

  start() {
    this.started = true;
  }

  stop() {
    FakeRecorder.data.forEach((d) => this.ondataavailable?.({ data: new Blob([d]) }));
    this.onstop?.();
  }
}

/** getUserMedia that resolves when the test says so, like a browser waiting for permission. */
function deferredMicrophone() {
  let grant: () => void = () => {};
  let deny: (error: Error) => void = () => {};
  const streams: FakeStream[] = [];
  const getUserMedia = vi.fn(
    () =>
      new Promise<FakeStream>((resolve, reject) => {
        grant = () => {
          const stream = new FakeStream();
          streams.push(stream);
          resolve(stream);
        };
        deny = reject;
      })
  );
  return { getUserMedia, streams, grant: () => grant(), deny: (e: Error) => deny(e) };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("useVoiceRecorder", () => {
  let mic: ReturnType<typeof deferredMicrophone>;
  // The recorder's clock, moved by hand
  let clock = 0;
  const now = () => clock;

  beforeEach(() => {
    clock = 0;
    FakeRecorder.instances = [];
    FakeRecorder.supported = "audio/webm";
    FakeRecorder.data = ["sound"];
    mic = deferredMicrophone();
    vi.stubGlobal("MediaRecorder", FakeRecorder);
    Object.defineProperty(navigator, "mediaDevices", {
      value: { getUserMedia: mic.getUserMedia },
      configurable: true,
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  async function startedRecorder(onRecorded = vi.fn(), onError = vi.fn()) {
    const recorder = useVoiceRecorder({ onRecorded, onError, now });
    const starting = recorder.startRecording();
    mic.grant();
    await starting;
    // Long enough to be a spoken command
    clock += MIN_RECORDING_MS;
    return { recorder, onRecorded, onError };
  }

  it("records in the most preferred supported format", async () => {
    FakeRecorder.supported = "audio/ogg;codecs=opus";
    const { recorder } = await startedRecorder();
    expect(recorder.recording.value).toBe(true);
    expect(FakeRecorder.instances[0].started).toBe(true);
    expect(FakeRecorder.instances[0].mimeType).toBe("audio/ogg;codecs=opus");
  });

  it("hands over the recording and releases the microphone when the button is released", async () => {
    const { recorder, onRecorded } = await startedRecorder();
    recorder.stopRecording();
    await flush();
    expect(recorder.recording.value).toBe(false);
    expect(onRecorded).toHaveBeenCalledOnce();
    const audio: Blob = onRecorded.mock.calls[0][0];
    expect(audio.type).toBe("audio/webm");
    expect(audio.size).toBe("sound".length);
    expect(mic.streams[0].released).toBe(true);
  });

  it("hands over an empty recording when the button was only tapped", async () => {
    const { recorder, onRecorded } = await startedRecorder();
    clock -= 1;
    recorder.stopRecording();
    await flush();
    expect(onRecorded).toHaveBeenCalledOnce();
    expect(onRecorded.mock.calls[0][0].size).toBe(0);
    expect(mic.streams[0].released).toBe(true);
  });

  it("does not record when the button is released while the microphone is being requested", async () => {
    const onRecorded = vi.fn();
    const recorder = useVoiceRecorder({ onRecorded, onError: vi.fn() });
    const starting = recorder.startRecording();
    recorder.stopRecording();
    mic.grant();
    await starting;
    expect(recorder.recording.value).toBe(false);
    expect(FakeRecorder.instances).toHaveLength(0);
    expect(mic.streams[0].released).toBe(true);
    expect(onRecorded).not.toHaveBeenCalled();
  });

  it("ignores a second press while the microphone is being requested", async () => {
    const recorder = useVoiceRecorder({ onRecorded: vi.fn(), onError: vi.fn() });
    const first = recorder.startRecording();
    await recorder.startRecording();
    mic.grant();
    await first;
    expect(mic.getUserMedia).toHaveBeenCalledOnce();
    expect(FakeRecorder.instances).toHaveLength(1);
  });

  it("reports a microphone that cannot be used", async () => {
    const onError = vi.fn();
    const recorder = useVoiceRecorder({ onRecorded: vi.fn(), onError });
    const starting = recorder.startRecording();
    mic.deny(new Error("Permission denied"));
    await starting;
    expect(onError).toHaveBeenCalledOnce();
    expect(recorder.recording.value).toBe(false);
  });

  it("dispose stops recording without handing it over", async () => {
    const { recorder, onRecorded } = await startedRecorder();
    recorder.dispose();
    await flush();
    expect(onRecorded).not.toHaveBeenCalled();
    expect(mic.streams[0].released).toBe(true);
  });

  it("does not start recording once disposed", async () => {
    const recorder = useVoiceRecorder({ onRecorded: vi.fn(), onError: vi.fn() });
    const starting = recorder.startRecording();
    recorder.dispose();
    mic.grant();
    await starting;
    expect(FakeRecorder.instances).toHaveLength(0);
    expect(mic.streams[0].released).toBe(true);
  });
});

describe("blobToBase64", () => {
  it("returns the content without the data URL prefix", async () => {
    expect(await blobToBase64(new Blob(["hello"], { type: "audio/webm" }))).toBe(btoa("hello"));
  });
});
