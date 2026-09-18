package com.gsc.protocol;

import com.gsc.command.CommandType;
import java.util.Map;

/** Telecommand transfer frame (uplink). {@code seq} is the frame sequence number N(S). */
public record TcFrame(long seq, CommandType type, Map<String, Object> args) {
}
