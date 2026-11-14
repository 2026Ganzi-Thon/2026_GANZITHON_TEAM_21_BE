package com.ganzithon.backend.bead;

import com.ganzithon.backend.api.ApiException;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static com.ganzithon.backend.api.ApiModels.Color;

@Service
public class MusicService {

    private static final int RATE = 22050;
    private static final int SECONDS = 10;

    private final BeadService beads;

    private final Map<Color, byte[]> presets =
            new EnumMap<>(Color.class);

    public MusicService(BeadService beads) {
        this.beads = beads;

        presets.put(Color.YELLOW,
                wav(new int[]{72, 76, 79, 84, 79, 76, 74, 79}));

        presets.put(Color.ORANGE,
                wav(new int[]{69, 73, 76, 81, 76, 78, 73, 81}));

        presets.put(Color.PINK,
                wav(new int[]{65, 69, 72, 76, 72, 69, 67, 72}));

        presets.put(Color.RED,
                wav(new int[]{57, 60, 64, 67, 64, 60, 59, 57}));

        presets.put(Color.PURPLE,
                wav(new int[]{62, 65, 69, 72, 68, 65, 64, 62}));

        presets.put(Color.BLUE,
                wav(new int[]{60, 63, 67, 70, 67, 63, 62, 60}));

        presets.put(Color.GREEN,
                wav(new int[]{60, 64, 67, 71, 67, 64, 62, 60}));
    }

    // TODO(AI가 음악 자동 생성):
    // 후속 유료 기능에서 감정 1~2개 기반 AI 음원 제공자로 교체
    public byte[] audio(String actor, String beadId) {
        var bead = beads.accessible(actor, beadId);

        String color = (String) bead.get("music_color");

        if (color == null) {
            throw ApiException.missing();
        }

        return presets.get(Color.valueOf(color));
    }

    private static byte[] wav(int[] notes) {
        int sampleCount = RATE * SECONDS;
        int dataSize = sampleCount * 2;

        ByteBuffer buffer = ByteBuffer
                .allocate(44 + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(RATE);
        buffer.putInt(RATE * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);

        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / RATE;

            int beat = Math.min(
                    (int) (t / 1.25),
                    notes.length - 1
            );

            double local = t % 1.25;

            double frequency = 440
                    * Math.pow(2, (notes[beat] - 69) / 12.0);

            double envelope =
                    Math.min(local / 0.03, 1)
                            * Math.exp(-local * 3)
                            * Math.min((SECONDS - t) / 0.3, 1);

            double sample = (
                    Math.sin(2 * Math.PI * frequency * local)
                            + 0.18 * Math.sin(
                            4 * Math.PI * frequency * local
                    )
            ) * envelope * 0.22;

            buffer.putShort(
                    (short) (
                            Math.max(-1, Math.min(1, sample)) * 32767
                    )
            );
        }

        return buffer.array();
    }
}