package com.gsc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gsc.spacecraft.SpacecraftMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TmFrameCodecTest {

    private static final Instant T = Instant.parse("2026-09-18T06:00:00Z");
    private static final TmFrame FRAME = new TmFrame(
            new TmSample(42, T, 73.25, 18.5, -0.18, 0.21, 0.14, SpacecraftMode.MISSION, true, false, true),
            new Clcw(9, true),
            List.of(new ExecutionReport(8, true, "PONG (링크 정상)", T),
                    new ExecutionReport(7, false, "탑재체는 MISSION 모드에서만 켤 수 있음", T)));

    @Test
    void encodedFrameDecodesBackToTheSameTelemetry() {
        Optional<TmFrame> decoded = TmFrameCodec.decode(TmFrameCodec.encode(FRAME));

        assertThat(decoded).isPresent();
        TmSample sample = decoded.get().sample();
        assertThat(sample.seqCount()).isEqualTo(42);
        assertThat(sample.scTime()).isEqualTo(T);
        assertThat(sample.batteryPct()).isCloseTo(73.25, within(0.01));
        assertThat(sample.tempC()).isCloseTo(18.5, within(0.01));
        assertThat(sample.mode()).isEqualTo(SpacecraftMode.MISSION);
        assertThat(sample.heaterOn()).isTrue();
        assertThat(sample.payloadOn()).isFalse();
        assertThat(sample.sunlit()).isTrue();
        assertThat(decoded.get().clcw()).isEqualTo(new Clcw(9, true));
        assertThat(decoded.get().recentExecutions()).hasSize(2);
        assertThat(decoded.get().recentExecutions().get(0).message()).isEqualTo("PONG (링크 정상)");
        assertThat(decoded.get().recentExecutions().get(1).success()).isFalse();
    }

    @Test
    void everySingleBitFlipIsDetected() {
        byte[] encoded = TmFrameCodec.encode(FRAME);

        for (int bit = 0; bit < encoded.length * 8; bit++) {
            byte[] corrupted = encoded.clone();
            corrupted[bit / 8] ^= (byte) (1 << (bit % 8));

            assertThat(TmFrameCodec.decode(corrupted))
                    .as("비트 %d 를 뒤집은 프레임은 폐기되어야 한다", bit)
                    .isEmpty();
        }
    }

    @Test
    void frameWithoutExecutionReportsRoundTrips() {
        TmFrame bare = new TmFrame(FRAME.sample(), new Clcw(1, false), List.of());

        Optional<TmFrame> decoded = TmFrameCodec.decode(TmFrameCodec.encode(bare));

        assertThat(decoded).isPresent();
        assertThat(decoded.get().recentExecutions()).isEmpty();
    }
}
