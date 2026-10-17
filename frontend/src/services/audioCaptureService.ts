
export class AudioCaptureService {
  private stream: MediaStream | null = null;
  private context: AudioContext | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private worklet: AudioWorkletNode | null = null;

  async start(
    onAudioChunk: (samples: Float32Array) => void
  ): Promise<number> {

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        channelCount: 1
      }
    });

    this.context = new AudioContext();

    await this.context.audioWorklet.addModule(
      "/audio-processor.js"
    );

    this.source =
      this.context.createMediaStreamSource(this.stream);

    this.worklet = new AudioWorkletNode(
      this.context,
      "soundmatch-processor"
    );

    this.worklet.port.onmessage = (event) => {
      onAudioChunk(new Float32Array(event.data));
    };

    this.source.connect(this.worklet);

    // Mantiene activo el grafo sin reproducir
    // el sonido capturado por los altavoces.
    const silentOutput = this.context.createGain();
    silentOutput.gain.value = 0;

    this.worklet.connect(silentOutput);
    silentOutput.connect(this.context.destination);

    return this.context.sampleRate;
  }

  async stop() {
    this.worklet?.disconnect();
    this.source?.disconnect();

    this.stream?.getTracks().forEach(
      track => track.stop()
    );

    await this.context?.close();

    this.stream = null;
    this.context = null;
    this.source = null;
    this.worklet = null;
  }
}
