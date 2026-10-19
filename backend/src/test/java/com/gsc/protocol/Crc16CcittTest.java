package com.gsc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Crc16CcittTest {

    /** CRC-16/CCITT-FALSE 표준 검증값: "123456789" -> 0x29B1 */
    @Test
    void matchesTheStandardCheckValue() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);

        assertThat(Crc16Ccitt.of(check)).isEqualTo(0x29B1);
    }

    @Test
    void singleBitFlipChangesTheResult() {
        byte[] data = {0x10, 0x20, 0x30, 0x40};
        int original = Crc16Ccitt.of(data);

        data[2] ^= 0x01;

        assertThat(Crc16Ccitt.of(data)).isNotEqualTo(original);
    }
}
