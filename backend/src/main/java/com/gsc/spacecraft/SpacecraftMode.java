package com.gsc.spacecraft;

public enum SpacecraftMode {
    /** Normal housekeeping, payload off. */
    NOMINAL(1),
    /** Payload operations allowed. */
    MISSION(2),
    /** Minimal power; entered on command or autonomously on low battery. */
    SAFE(3);

    /** 전송 프레임에 실리는 고정 코드. 열거형 선언 순서와 무관하게 값을 유지한다. */
    private final int code;

    SpacecraftMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SpacecraftMode fromCode(int code) {
        for (SpacecraftMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("알 수 없는 운용 모드 코드: " + code);
    }
}
