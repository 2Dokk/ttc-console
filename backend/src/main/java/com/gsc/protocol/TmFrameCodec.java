package com.gsc.protocol;

import com.gsc.spacecraft.SpacecraftMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 실시간 텔레메트리 프레임의 바이트 표현. 원격명령과 마찬가지로 끝에 CRC를 붙여,
 * 내려오는 도중 손상된 프레임을 지상에서 검출해 버릴 수 있게 한다.
 *
 * <pre>
 *   0..7    프레임 카운터
 *   8..15   위성 시각 (epoch milli)
 *   16..19  배터리 %        (float)
 *   20..23  온도 °C         (float)
 *   24..35  자세 롤/피치/요 (float 3개)
 *   36      운용 모드 코드
 *   37      상태 비트 (0: 히터, 1: 탑재체, 2: 일조)
 *   38..45  CLCW V(R)
 *   46      CLCW 재전송 요청 플래그
 *   47      실행 보고 개수
 *   48..    실행 보고 N개 (순번 8 · 성공 1 · 시각 8 · 메시지 길이 2 · 메시지)
 *   끝 2바이트  CRC-16/CCITT
 * </pre>
 */
public final class TmFrameCodec {

    static final int HEADER_BYTES = 48;
    static final int CRC_BYTES = 2;
    private static final int REPORT_FIXED_BYTES = 19;

    private TmFrameCodec() {
    }

    public static byte[] encode(TmFrame frame) {
        TmSample s = frame.sample();
        List<byte[]> messages = new ArrayList<>();
        int reportBytes = 0;
        for (ExecutionReport report : frame.recentExecutions()) {
            byte[] message = report.message().getBytes(StandardCharsets.UTF_8);
            messages.add(message);
            reportBytes += REPORT_FIXED_BYTES + message.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + reportBytes + CRC_BYTES);
        buffer.putLong(s.seqCount());
        buffer.putLong(s.scTime().toEpochMilli());
        buffer.putFloat((float) s.batteryPct());
        buffer.putFloat((float) s.tempC());
        buffer.putFloat((float) s.rollDeg());
        buffer.putFloat((float) s.pitchDeg());
        buffer.putFloat((float) s.yawDeg());
        buffer.put((byte) s.mode().code());
        buffer.put(flags(s));
        buffer.putLong(frame.clcw().vr());
        buffer.put((byte) (frame.clcw().retransmit() ? 1 : 0));
        buffer.put((byte) frame.recentExecutions().size());
        for (int i = 0; i < frame.recentExecutions().size(); i++) {
            ExecutionReport report = frame.recentExecutions().get(i);
            byte[] message = messages.get(i);
            buffer.putLong(report.seq());
            buffer.put((byte) (report.success() ? 1 : 0));
            buffer.putLong(report.executedAt().toEpochMilli());
            buffer.putShort((short) message.length);
            buffer.put(message);
        }
        buffer.putShort((short) Crc16Ccitt.of(buffer.array(), 0, HEADER_BYTES + reportBytes));
        return buffer.array();
    }

    /** CRC가 맞지 않거나 형식이 깨진 프레임이면 비어 있는 값을 돌려준다. */
    public static Optional<TmFrame> decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES + CRC_BYTES) {
            return Optional.empty();
        }
        int payload = bytes.length - CRC_BYTES;
        int computed = Crc16Ccitt.of(bytes, 0, payload);
        int received = ByteBuffer.wrap(bytes, payload, CRC_BYTES).getShort() & 0xFFFF;
        if (computed != received) {
            return Optional.empty();
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, payload);
            long seqCount = buffer.getLong();
            Instant scTime = Instant.ofEpochMilli(buffer.getLong());
            float battery = buffer.getFloat();
            float temp = buffer.getFloat();
            float roll = buffer.getFloat();
            float pitch = buffer.getFloat();
            float yaw = buffer.getFloat();
            SpacecraftMode mode = SpacecraftMode.fromCode(buffer.get() & 0xFF);
            byte flags = buffer.get();
            Clcw clcw = new Clcw(buffer.getLong(), buffer.get() != 0);
            int reportCount = buffer.get() & 0xFF;

            List<ExecutionReport> reports = new ArrayList<>(reportCount);
            for (int i = 0; i < reportCount; i++) {
                long seq = buffer.getLong();
                boolean success = buffer.get() != 0;
                Instant executedAt = Instant.ofEpochMilli(buffer.getLong());
                int length = buffer.getShort() & 0xFFFF;
                byte[] message = new byte[length];
                buffer.get(message);
                reports.add(new ExecutionReport(seq, success, new String(message, StandardCharsets.UTF_8),
                        executedAt));
            }
            if (buffer.hasRemaining()) {
                return Optional.empty();
            }

            TmSample sample = new TmSample(seqCount, scTime, battery, temp, roll, pitch, yaw, mode,
                    (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0);
            return Optional.of(new TmFrame(sample, clcw, reports));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte flags(TmSample sample) {
        int flags = 0;
        if (sample.heaterOn()) {
            flags |= 0x01;
        }
        if (sample.payloadOn()) {
            flags |= 0x02;
        }
        if (sample.sunlit()) {
            flags |= 0x04;
        }
        return (byte) flags;
    }
}
