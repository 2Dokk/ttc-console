package com.gsc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.gsc.command.CommandType;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TcFrameCodecTest {

    private static final TcFrame FRAME =
            new TcFrame(7, CommandType.SET_MODE, Map.of("mode", "MISSION"));

    @Test
    void encodedFrameDecodesBackToTheSameCommand() {
        Optional<TcFrame> decoded = TcFrameCodec.decode(TcFrameCodec.encode(FRAME));

        assertThat(decoded).isPresent();
        assertThat(decoded.get().seq()).isEqualTo(7);
        assertThat(decoded.get().type()).isEqualTo(CommandType.SET_MODE);
        assertThat(decoded.get().args()).containsEntry("mode", "MISSION");
    }

    @Test
    void everySingleBitFlipIsDetected() {
        byte[] encoded = TcFrameCodec.encode(FRAME);

        for (int bit = 0; bit < encoded.length * 8; bit++) {
            byte[] corrupted = encoded.clone();
            corrupted[bit / 8] ^= (byte) (1 << (bit % 8));

            assertThat(TcFrameCodec.decode(corrupted))
                    .as("비트 %d 를 뒤집은 프레임은 폐기되어야 한다", bit)
                    .isEmpty();
        }
    }

    @Test
    void truncatedOrEmptyFrameIsDiscarded() {
        byte[] encoded = TcFrameCodec.encode(FRAME);

        assertThat(TcFrameCodec.decode(Arrays.copyOf(encoded, encoded.length - 1))).isEmpty();
        assertThat(TcFrameCodec.decode(new byte[0])).isEmpty();
        assertThat(TcFrameCodec.decode(null)).isEmpty();
    }
}
