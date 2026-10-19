package com.gsc.command;

import com.gsc.spacecraft.SpacecraftMode;
import java.util.Map;

public enum CommandType {
    PING(1),
    SET_MODE(2),
    HEATER(3),
    PAYLOAD_POWER(4);

    /** 전송 프레임에 실리는 고정 코드. 열거형 선언 순서가 바뀌어도 통신 호환성이 깨지지 않도록 값을 고정한다. */
    private final int code;

    CommandType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CommandType fromCode(int code) {
        for (CommandType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 명령 코드: " + code);
    }

    /** Ground-side syntax check. Whether the spacecraft accepts it in its current state is decided onboard. */
    public void validate(Map<String, Object> args) {
        switch (this) {
            case PING -> { }
            case SET_MODE -> {
                Object mode = args.get("mode");
                try {
                    SpacecraftMode.valueOf(String.valueOf(mode));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("SET_MODE에는 args.mode(NOMINAL|MISSION|SAFE)가 필요합니다");
                }
            }
            case HEATER, PAYLOAD_POWER -> {
                if (!(args.get("on") instanceof Boolean)) {
                    throw new IllegalArgumentException(this + "에는 true/false 값의 args.on이 필요합니다");
                }
            }
        }
    }
}
