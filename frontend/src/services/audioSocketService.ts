
export type RecommendationMessage = {
  type: "recommendations";
  sessionId: string;
  sequence: number;
  windowStartSeconds: number;
  windowEndSeconds: number;
  processingMs: number;
  recommendations: Array<{
    song_id: number;
    title: string;
    artist: string | null;
    similarity: number;
  }>;
};

export class AudioSocketService {
  private socket: WebSocket | null = null;
  private ready = false;

  connect(
    sampleRate: number,
    onRecommendations: (data: RecommendationMessage) => void,
    onError: (message: string) => void
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(
        "ws://localhost:8080/ws/listen"
      );

      this.socket = socket;

      socket.onopen = () => {
        socket.send(JSON.stringify({
          type: "start",
          sampleRate,
          channels: 1,
          format: "pcm_f32le",
          windowSeconds: 10,
          hopSeconds: 5
        }));
      };

      socket.onmessage = (event) => {
        if (typeof event.data !== "string") return;

        const message = JSON.parse(event.data);

        if (message.type === "ready") {
          this.ready = true;
          resolve();
        }

        if (message.type === "recommendations") {
          onRecommendations(message);
        }

        if (message.type === "error") {
          onError(message.message);
        }
      };

      socket.onerror = () => {
        onError("Error de conexión WebSocket");
        reject(new Error("WebSocket error"));
      };

      socket.onclose = () => {
        this.ready = false;
      };
    });
  }

  sendAudio(samples: Float32Array) {
    if (!this.ready || !this.socket) return;

    if (this.socket.readyState !== WebSocket.OPEN) {
      return;
    }

    // Evitar acumulación ilimitada de audio.
    if (this.socket.bufferedAmount > 1_000_000) {
      return;
    }

    const bytes = new Uint8Array(samples.length * 4);
    const view = new DataView(bytes.buffer);

    for (let i = 0; i < samples.length; i++) {
      view.setFloat32(i * 4, samples[i], true);
    }

    this.socket.send(bytes.buffer);
  }

  close() {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({
        type: "stop"
      }));
      this.socket.close();
    }

    this.ready = false;
    this.socket = null;
  }
}
