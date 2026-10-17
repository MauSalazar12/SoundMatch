package com.soundmatch.websocket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AudioWindowAssemblerTest {
    @ParameterizedTest @ValueSource(ints = {44100, 48000})
    void exactOverlappingWindowsWithIrregularBlocks(int rate) {
        var assembler = new AudioWindowAssembler(rate, 10, 5);
        var windows = new ArrayList<AudioWindowAssembler.Window>();
        int total = rate * 20;
        for (int offset = 0; offset < total;) {
            int size = Math.min(7919, total - offset);
            float[] samples = new float[size];
            for (int i = 0; i < size; i++) samples[i] = offset + i;
            assembler.append(samples, windows::add);
            offset += size;
            assertEquals(offset < rate * 10 ? 0 : 1 + (offset - rate * 10) / (rate * 5), windows.size());
        }
        assertEquals(3, windows.size());
        for (int i = 0; i < 3; i++) {
            var window = windows.get(i);
            assertEquals(i + 1, window.sequence());
            assertEquals(i * 5, window.startSeconds());
            assertEquals(i * 5 + 10, window.endSeconds());
            var pcm = ByteBuffer.wrap(window.pcm()).order(ByteOrder.LITTLE_ENDIAN);
            for (int j = 0; j < rate * 10; j++) assertEquals((float) (i * rate * 5 + j), pcm.getFloat());
        }
        assertEquals(total, assembler.receivedSamples());
        assembler.close();
        assertThrows(IllegalStateException.class, () -> assembler.append(new float[1], windows::add));
    }
}
