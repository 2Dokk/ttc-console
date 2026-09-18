package com.gsc.events;

import com.gsc.clock.SimulationClock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Operator-facing event timeline (AOS/LOS, retransmissions, playback, limit violations). */
@Component
public class OpsEventLog {

    public enum Level { INFO, WARN }

    public record OpsEvent(long id, Instant missionTime, Level level, String category, String message) {}

    private static final Logger log = LoggerFactory.getLogger(OpsEventLog.class);
    private static final int CAPACITY = 300;

    private final SimulationClock clock;
    private final SimpMessagingTemplate messaging;
    private final Deque<OpsEvent> events = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong();

    public OpsEventLog(SimulationClock clock, SimpMessagingTemplate messaging) {
        this.clock = clock;
        this.messaging = messaging;
    }

    public void info(String category, String message) {
        add(Level.INFO, category, message);
    }

    public void warn(String category, String message) {
        add(Level.WARN, category, message);
    }

    public synchronized List<OpsEvent> recent(int limit) {
        List<OpsEvent> all = new ArrayList<>(events);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    private void add(Level level, String category, String message) {
        OpsEvent event = new OpsEvent(ids.incrementAndGet(), clock.now(), level, category, message);
        synchronized (this) {
            events.addLast(event);
            if (events.size() > CAPACITY) {
                events.removeFirst();
            }
        }
        log.info("[{}] {}", category, message);
        messaging.convertAndSend("/topic/events", event);
    }
}
