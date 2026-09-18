package com.gsc.telemetry;

import com.gsc.clock.SimulationClock;
import com.gsc.command.CommandService;
import com.gsc.config.GscProperties;
import com.gsc.events.OpsEventLog;
import com.gsc.protocol.PlaybackBatch;
import com.gsc.protocol.TmFrame;
import com.gsc.protocol.TmSample;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Ground reception: stores telemetry, feeds CLCW and execution reports to command verification,
 * detects lost real-time frames from gaps in the frame counter, and runs fixed limit checks.
 */
@Service
public class TelemetryIngestService {

    /** Fixed operator limits (not learned): below/above these the operator gets a warning. */
    static final double BATTERY_LOW_PCT = 25.0;
    static final double TEMP_LOW_C = -5.0;
    static final double TEMP_HIGH_C = 35.0;

    public record Stats(long realtimeFrames, long framesMissing, long playbackSamples) {}

    private final TelemetryRepository repo;
    private final CommandService commands;
    private final SimulationClock clock;
    private final OpsEventLog events;
    private final SimpMessagingTemplate messaging;
    private final long satelliteId;

    private long lastRealtimeSeq = -1;
    private long realtimeFrames;
    private long framesMissing;
    private long playbackSamples;
    private boolean playbackInProgress;
    private String lastMode;
    private boolean batteryLow;
    private boolean tempOutOfLimits;

    public TelemetryIngestService(TelemetryRepository repo, CommandService commands, SimulationClock clock,
                                  OpsEventLog events, SimpMessagingTemplate messaging, GscProperties props) {
        this.repo = repo;
        this.commands = commands;
        this.clock = clock;
        this.events = events;
        this.messaging = messaging;
        this.satelliteId = props.satelliteId();
    }

    public synchronized void onRealtimeFrame(TmFrame frame) {
        TmSample sample = frame.sample();
        if (lastRealtimeSeq >= 0 && sample.seqCount() > lastRealtimeSeq + 1) {
            framesMissing += sample.seqCount() - lastRealtimeSeq - 1;
        }
        lastRealtimeSeq = sample.seqCount();
        realtimeFrames++;

        TelemetryPoint point = toPoint(sample, TelemetryPoint.REALTIME, clock.now());
        repo.insertAll(satelliteId, List.of(point));
        checkLimits(point);
        commands.onClcw(frame.clcw());
        commands.onExecutionReports(frame.recentExecutions());
        messaging.convertAndSend("/topic/telemetry", point);
    }

    public synchronized void onPlayback(PlaybackBatch batch) {
        Instant now = clock.now();
        List<TelemetryPoint> points = batch.samples().stream()
                .map(s -> toPoint(s, TelemetryPoint.PLAYBACK, now)).toList();
        if (!playbackInProgress && !points.isEmpty()) {
            playbackInProgress = true;
            events.info("TM", String.format("레코더 재생 시작: %s 이후 저장된 샘플 %d개",
                    points.get(0).recordedAt(), points.size() + batch.remaining()));
        }
        repo.insertAll(satelliteId, points);
        playbackSamples += points.size();
        commands.onExecutionReports(batch.recentExecutions());
        messaging.convertAndSend("/topic/telemetry/playback", Map.of("samples", points, "remaining", batch.remaining()));
        if (batch.remaining() == 0 && playbackInProgress) {
            playbackInProgress = false;
            events.info("TM", "레코더 재생 완료, 교신 불가 구간의 이력을 채웠습니다");
        }
    }

    public List<TelemetryPoint> history(Instant since, int limit) {
        return repo.findSince(satelliteId, since, limit);
    }

    public synchronized Stats stats() {
        return new Stats(realtimeFrames, framesMissing, playbackSamples);
    }

    private void checkLimits(TelemetryPoint p) {
        if (lastMode != null && !lastMode.equals(p.mode())) {
            events.warn("TM", "위성 운용 모드 변경: " + lastMode + " → " + p.mode());
        }
        lastMode = p.mode();

        boolean low = p.batteryPct() < BATTERY_LOW_PCT;
        if (low && !batteryLow) {
            events.warn("LIMIT", String.format("배터리 %.1f%%, 하한 %.0f%% 미만", p.batteryPct(), BATTERY_LOW_PCT));
        }
        batteryLow = low;

        boolean tempBad = p.tempC() < TEMP_LOW_C || p.tempC() > TEMP_HIGH_C;
        if (tempBad && !tempOutOfLimits) {
            events.warn("LIMIT", String.format("온도 %.1f°C, 허용 범위 [%.0f, %.0f] 벗어남", p.tempC(), TEMP_LOW_C,
                    TEMP_HIGH_C));
        }
        tempOutOfLimits = tempBad;
    }

    private static TelemetryPoint toPoint(TmSample s, String source, Instant receivedAt) {
        return new TelemetryPoint(s.seqCount(), source, s.scTime(), receivedAt, s.batteryPct(), s.tempC(),
                s.rollDeg(), s.pitchDeg(), s.yawDeg(), s.mode().name(), s.heaterOn(), s.payloadOn(), s.sunlit());
    }
}
