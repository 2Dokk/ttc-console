package com.gsc.protocol;

/**
 * CRC-16/CCITT-FALSE (다항식 0x1021, 초기값 0xFFFF).
 * CCSDS TC/TM 전송 프레임의 오류 검출에 쓰이는 방식으로, 전송 중 비트가 뒤집힌 프레임을 수신 측에서 걸러낸다.
 */
public final class Crc16Ccitt {

    private static final int POLYNOMIAL = 0x1021;
    private static final int INITIAL = 0xFFFF;

    private Crc16Ccitt() {
    }

    public static int of(byte[] data, int offset, int length) {
        int crc = INITIAL;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ POLYNOMIAL : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public static int of(byte[] data) {
        return of(data, 0, data.length);
    }
}
