package com.soundmatch.websocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Fixed-capacity ring. Its owner serializes access; emitted byte arrays never alias the ring. */
public final class AudioWindowAssembler implements AutoCloseable {
    public record Window(long sequence, long startSample, long endSample, int sampleRate,
                         byte[] pcm, long assembledNanos) {
        public double startSeconds() { return (double) startSample / sampleRate; }
        public double endSeconds() { return (double) endSample / sampleRate; }
    }
    private float[] ring;
    private final int sampleRate, hopSamples;
    private int position;
    private long received, nextEnd, sequence;

    public AudioWindowAssembler(int sampleRate, int windowSeconds, int hopSeconds) {
        if (sampleRate < 1 || windowSeconds < 1 || hopSeconds < 1 || hopSeconds > windowSeconds)
            throw new IllegalArgumentException("Invalid window sizes");
        this.sampleRate = sampleRate;
        this.hopSamples = Math.multiplyExact(sampleRate, hopSeconds);
        this.ring = new float[Math.multiplyExact(sampleRate, windowSeconds)];
        nextEnd = ring.length;
    }

    public void append(float[] samples, Consumer<Window> output) {
        if (ring == null) throw new IllegalStateException("Assembler closed");
        for (float sample : samples) {
            ring[position] = sample;
            position = (position + 1) % ring.length;
            if (++received == nextEnd) {
                var copy = ByteBuffer.allocate(ring.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < ring.length; i++) copy.putFloat(ring[(position + i) % ring.length]);
                output.accept(new Window(++sequence, received - ring.length, received, sampleRate,
                        copy.array(), System.nanoTime()));
                nextEnd += hopSamples;
            }
        }
    }
    public long receivedSamples() { return received; }
    @Override public void close() { ring = null; }
}
