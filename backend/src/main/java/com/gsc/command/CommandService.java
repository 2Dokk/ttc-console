package com.gsc.command;

import com.gsc.clock.SimulationClock;
import com.gsc.config.GscProperties;
import com.gsc.events.OpsEventLog;
import com.gsc.protocol.Clcw;
import com.gsc.protocol.ExecutionReport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator-facing command handling plus ground-side verification: CLCW acknowledgements and
 * execution reports coming back in telemetry. Transmission itself is the dispatcher's job.
 */
@Service
public class CommandService {

    private final CommandRepository repo;
    private final SimulationClock clock;
    private final OpsEventLog events;
    private final SimpMessagingTemplate messaging;
    private final long defaultSatelliteId;

    public CommandService(CommandRepository repo, SimulationClock clock, OpsEventLog events,
                          SimpMessagingTemplate messaging, GscProperties props) {
        this.repo = repo;
        this.clock = clock;
        this.events = events;
        this.messaging = messaging;
        this.defaultSatelliteId = props.satelliteId();
    }

    /** Always queues: the dispatcher decides when it can go on the air. */
    public Command create(CreateCommandRequest request) {
        Map<String, Object> args = request.args() == null ? Map.of() : request.args();
        request.type().validate(args);
        long satelliteId = request.satelliteId() == null ? defaultSatelliteId : request.satelliteId();
        if (satelliteId != defaultSatelliteId) {
            throw new IllegalArgumentException("위성 " + satelliteId + "은(는) 이 지상국에서 추적하지 않습니다");
        }
        Instant now = clock.now();
        Instant expiresAt = request.expiresInMinutes() == null
                ? null : now.plus(Duration.ofMinutes(request.expiresInMinutes()));
        Command command = repo.insert(satelliteId, request.type(), args, now, expiresAt);
        events.info("CMD", String.format("#%d %s 대기열 등록", command.id(), describe(command)));
        publish(command);
        return command;
    }

    public List<Command> recent(int limit) {
        return repo.findRecent(limit);
    }

    public Command cancel(long id) {
        Command command = repo.cancel(id).orElseThrow(() ->
                new IllegalArgumentException("명령 #" + id + "은(는) 대기 상태가 아니어서 취소할 수 없습니다"));
        events.info("CMD", String.format("#%d 운용자가 취소", id));
        publish(command);
        return command;
    }

    public void onClcw(Clcw clcw) {
        List<Command> acked = repo.ackBelow(defaultSatelliteId, clcw.vr(), clock.now());
        if (acked.isEmpty()) {
            return;
        }
        acked.forEach(this::publish);
        events.info("FOP", String.format("CLCW V(R)=%d: seq %s 수신 확인", clcw.vr(),
                acked.stream().map(c -> String.valueOf(c.seq())).toList()));
    }

    /** Reports repeat in consecutive frames; the conditional update makes duplicates no-ops. */
    public void onExecutionReports(List<ExecutionReport> reports) {
        Instant now = clock.now();
        for (ExecutionReport report : reports) {
            repo.markExecuted(defaultSatelliteId, report.seq(), report.success(), report.message(),
                    report.executedAt(), now).ifPresent(command -> {
                        publish(command);
                        String line = String.format("#%d %s %s: %s", command.id(), describe(command),
                                report.success() ? "실행 완료" : "거부됨", report.message());
                        if (report.success()) {
                            events.info("CMD", line);
                        } else {
                            events.warn("CMD", line);
                        }
                    });
        }
    }

    public void publish(Command command) {
        messaging.convertAndSend("/topic/commands", command);
    }

    static String describe(Command command) {
        return command.args().isEmpty() ? command.type().name() : command.type() + " " + command.args();
    }
}
