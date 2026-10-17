
class SoundMatchProcessor extends AudioWorkletProcessor {
  constructor() {
    super();

    this.buffers = [];
    this.samplesAvailable = 0;

    // Aproximadamente 100 ms de audio
    this.chunkSize = Math.round(sampleRate * 0.1);
  }

  process(inputs) {
    const input = inputs[0];

    if (!input || input.length === 0) {
      return true;
    }

    // Mezclar canales a mono
    const length = input[0].length;
    const mono = new Float32Array(length);

    for (let channel = 0; channel < input.length; channel++) {
      for (let i = 0; i < length; i++) {
        mono[i] += input[channel][i] / input.length;
      }
    }

    this.buffers.push(mono);
    this.samplesAvailable += mono.length;

    while (this.samplesAvailable >= this.chunkSize) {
      const output = new Float32Array(this.chunkSize);

      let written = 0;

      while (written < output.length) {
        const current = this.buffers[0];
        const count = Math.min(
          current.length,
          output.length - written
        );

        output.set(current.subarray(0, count), written);
        written += count;

        if (count === current.length) {
          this.buffers.shift();
        } else {
          this.buffers[0] = current.subarray(count);
        }
      }

      this.samplesAvailable -= output.length;

      this.port.postMessage(output, [output.buffer]);
    }

    return true;
  }
}

registerProcessor(
  "soundmatch-processor",
  SoundMatchProcessor
);
