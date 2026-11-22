/**
 * Push-to-talk recording: records from the microphone while the button is held and hands the
 * recording over when it is released.
 */

import { ref, onUnmounted, getCurrentInstance } from "vue";

/** Recording formats in order of preference (the first ones suit Whisper best). */
export const PREFERRED_FORMATS = ["audio/wav", "audio/ogg;codecs=opus", "audio/webm;codecs=opus", "audio/webm"];

type Log = (message: string, ...args: unknown[]) => void;

export interface VoiceRecorderOptions {
  /** Called with each finished recording (possibly empty, if the button was only tapped). */
  onRecorded: (audio: Blob) => void | Promise<void>;
  /** Called when the microphone cannot be used (e.g. permission denied). */
  onError: (error: unknown) => void;
  log?: Log;
}

export function useVoiceRecorder(options: VoiceRecorderOptions) {
  const log: Log = options.log ?? (() => {});
  const recording = ref(false);
  let recorder: MediaRecorder | null = null;
  let chunks: Blob[] = [];
  // The microphone stream while recording, released when recording stops or the recorder is disposed
  let micStream: MediaStream | null = null;
  // Whether the push-to-talk button is held, and whether the microphone is being requested
  let micPressed = false;
  let acquiringMic = false;
  let disposed = false;

  const releaseMicrophone = () => {
    micStream?.getTracks().forEach((track) => track.stop());
    micStream = null;
  };

  /** Start recording (the button was pressed). */
  const startRecording = async () => {
    micPressed = true;
    if (recording.value || acquiringMic) {
      log("Already recording, ignoring");
      return;
    }

    try {
      acquiringMic = true;
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } finally {
        acquiringMic = false;
      }
      micStream = stream;
      // The button was released (or the view closed) while the browser asked for permission
      if (!micPressed || disposed) {
        log("Button released before the microphone was ready, not recording");
        releaseMicrophone();
        return;
      }

      const mimeType = PREFERRED_FORMATS.find((format) => MediaRecorder.isTypeSupported(format));
      if (mimeType) log("Using audio format:", mimeType);
      const mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      recorder = mediaRecorder;
      chunks = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data);
      };

      mediaRecorder.onstop = async () => {
        // Release this recording's stream (a newer recording may have its own by now)
        stream.getTracks().forEach((track) => track.stop());
        if (micStream === stream) micStream = null;
        // Stopped because the view closed: there is nothing to send the recording to
        if (disposed) return;
        const type = mediaRecorder.mimeType || "audio/webm";
        const audio = new Blob(chunks, { type });
        log("Recording stopped, mime type:", type, "size:", audio.size);
        await options.onRecorded(audio);
      };

      mediaRecorder.start();
      recording.value = true;
      log("Recording started");
    } catch (error) {
      log("Error accessing microphone:", error);
      releaseMicrophone();
      options.onError(error);
    }
  };

  /** Stop recording (the button was released); the recording is handed to onRecorded. */
  const stopRecording = () => {
    micPressed = false;
    if (recorder && recording.value) {
      recorder.stop();
      recording.value = false;
    }
  };

  /** Stop recording without handing it over, and release the microphone. */
  const dispose = () => {
    disposed = true;
    if (recorder && recording.value) recorder.stop();
    releaseMicrophone();
  };

  // The composable can also be used outside a component (e.g. in tests)
  if (getCurrentInstance()) onUnmounted(dispose);

  return { recording, startRecording, stopRecording, dispose };
}

/** The base64 content of a blob, without the `data:...;base64,` prefix. */
export function blobToBase64(blob: Blob): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve((reader.result as string).split(",")[1]);
    reader.onerror = () => reject(reader.error ?? new Error("Could not read the recording"));
    reader.readAsDataURL(blob);
  });
}
