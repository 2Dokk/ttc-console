package com.gsc.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsc.command.CommandType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;

/**
 * 원격명령 프레임의 바이트 표현. 끝에 CRC를 붙여 수신 측이 전송 중 손상을 검출할 수 있게 한다.
 *
 * <pre>
 *   0..7   순번 N(S)        (8바이트)
 *   8      명령 코드        (1바이트)
 *   9..10  인자 길이        (2바이트)
 *   11..   인자 (UTF-8 JSON)
 *   끝 2바이트  CRC-16/CCITT (앞의 모든 바이트에 대해 계산)
 * </pre>
 *
 * 인자를 JSON 문자열로 담는 것은 이 프로젝트의 단순화이며, 실제 CCSDS 패킷은 명령마다 고정 형식의
 * 이진 필드를 사용한다.
 */
public final class TcFrameCodec {

    static final int HEADER_BYTES = 11;
    static final int CRC_BYTES = 2;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {};

    private TcFrameCodec() {
    }

    public static byte[] encode(TcFrame frame) {
        byte[] args;
        try {
            args = JSON.writeValueAsBytes(frame.args() == null ? Map.of() : frame.args());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("명령 인자를 프레임으로 변환할 수 없습니다", e);
        }
        if (args.length > 0xFFFF) {
            throw new IllegalArgumentException("명령 인자가 프레임 한도를 넘습니다");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + args.length + CRC_BYTES);
        buffer.putLong(frame.seq());
        buffer.put((byte) frame.type().code());
        buffer.putShort((short) args.length);
        buffer.put(args);
        buffer.putShort((short) Crc16Ccitt.of(buffer.array(), 0, HEADER_BYTES + args.length));
        return buffer.array();
    }

    /** 길이가 맞지 않거나 CRC가 틀리거나 해석할 수 없는 프레임이면 비어 있는 값을 돌려준다. */
    public static Optional<TcFrame> decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES + CRC_BYTES) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long seq = buffer.getLong();
        int typeCode = buffer.get() & 0xFF;
        int argsLength = buffer.getShort() & 0xFFFF;
        if (bytes.length != HEADER_BYTES + argsLength + CRC_BYTES) {
            return Optional.empty();
        }

        int computed = Crc16Ccitt.of(bytes, 0, HEADER_BYTES + argsLength);
        int received = ByteBuffer.wrap(bytes, HEADER_BYTES + argsLength, CRC_BYTES).getShort() & 0xFFFF;
        if (computed != received) {
            return Optional.empty();
        }

        byte[] args = new byte[argsLength];
        buffer.get(args);
        try {
            return Optional.of(new TcFrame(seq, CommandType.fromCode(typeCode), JSON.readValue(args, ARGS_TYPE)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
