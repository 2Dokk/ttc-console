package com.gsc.command;

import com.gsc.spacecraft.SpacecraftMode;
import java.util.Map;

public enum CommandType {
    PING,
    SET_MODE,
    HEATER,
    PAYLOAD_POWER;

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
