package com.gsc.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every state transition is a single conditional UPDATE (compare-and-set on {@code status}), so the
 * dispatcher thread, the downlink thread and HTTP requests can race without a lost update: e.g. a
 * late CLCW can never move an EXECUTED command back to ACKED, and a cancel can never hit a frame
 * that is already on the air.
 */
@Repository
public class CommandRepository {

    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Command> mapper;

    public CommandRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = this::map;
    }

    public Command insert(long satelliteId, CommandType type, Map<String, Object> args, Instant createdAt,
                          Instant expiresAt) {
        return jdbc.queryForObject("""
                insert into command (satellite_id, type, args, status, created_at, expires_at)
                values (?, ?, ?, 'PENDING', ?, ?)
                returning *""", mapper, satelliteId, type.name(), write(args), utc(createdAt), utc(expiresAt));
    }

    public Optional<Command> findById(long id) {
        return jdbc.query("select * from command where id = ?", mapper, id).stream().findFirst();
    }

    public List<Command> findRecent(int limit) {
        return jdbc.query("select * from command order by id desc limit ?", mapper, limit);
    }

    /** Frames on the air awaiting acknowledgement, in sequence order (the FOP sent queue). */
    public List<Command> findOutstanding(long satelliteId) {
        return jdbc.query("select * from command where satellite_id = ? and status = 'SENT' order by seq",
                mapper, satelliteId);
    }

    /** Queued commands in the order the operator issued them. */
    public List<Command> findPending(long satelliteId, int limit) {
        return jdbc.query("""
                select * from command where satellite_id = ? and status = 'PENDING'
                order by created_at, id limit ?""", mapper, satelliteId, limit);
    }

    /** Latest mission-time stamp on any command, so a restart never rewinds mission time behind history. */
    public Optional<Instant> latestActivity() {
        OffsetDateTime latest = jdbc.queryForObject("""
                select max(greatest(created_at, last_sent_at, acked_at, executed_at)) from command""",
                OffsetDateTime.class);
        return Optional.ofNullable(latest).map(OffsetDateTime::toInstant);
    }

    public long maxSeq(long satelliteId) {
        Long max = jdbc.queryForObject("select max(seq) from command where satellite_id = ?", Long.class,
                satelliteId);
        return max == null ? 0 : max;
    }

    /** PENDING -> SENT (first transmission) or SENT -> SENT (retransmission). */
    public Optional<Command> markSent(long id, long seq, Instant now) {
        return jdbc.query("""
                update command
                   set status = 'SENT', seq = ?, attempts = attempts + 1,
                       first_sent_at = coalesce(first_sent_at, ?), last_sent_at = ?
                 where id = ? and status in ('PENDING', 'SENT')
                returning *""", mapper, seq, utc(now), utc(now), id).stream().findFirst();
    }

    /** CLCW V(R) acknowledges every frame with seq below it. */
    public List<Command> ackBelow(long satelliteId, long vr, Instant now) {
        return jdbc.query("""
                update command set status = 'ACKED', acked_at = ?
                 where satellite_id = ? and status = 'SENT' and seq < ?
                returning *""", mapper, utc(now), satelliteId, vr);
    }

    /** An execution report implies delivery, so it may also arrive while the command is still SENT. */
    public Optional<Command> markExecuted(long satelliteId, long seq, boolean success, String message,
                                          Instant executedAt, Instant now) {
        return jdbc.query("""
                update command
                   set status = ?, executed_at = ?, result_message = ?, acked_at = coalesce(acked_at, ?)
                 where satellite_id = ? and seq = ? and status in ('SENT', 'ACKED')
                returning *""", mapper, success ? "EXECUTED" : "REJECTED", utc(executedAt), message,
                utc(now), satelliteId, seq).stream().findFirst();
    }

    /** Only never-transmitted commands expire; anything already on the air must be seen through. */
    public List<Command> expire(Instant now) {
        return jdbc.query("""
                update command set status = 'EXPIRED', result_message = '송신 전 유효 기한 만료'
                 where status = 'PENDING' and expires_at is not null and expires_at <= ?
                returning *""", mapper, utc(now));
    }

    public Optional<Command> cancel(long id) {
        return jdbc.query("""
                update command set status = 'CANCELLED', result_message = '운용자 취소'
                 where id = ? and status = 'PENDING'
                returning *""", mapper, id).stream().findFirst();
    }

    /**
     * Startup recovery. The simulated spacecraft restarts with the ground, so frames on the air are
     * re-queued (they get fresh sequence numbers) and accepted-but-unverified commands are failed.
     */
    public void recoverAfterRestart() {
        jdbc.update("update command set status = 'PENDING', seq = null where status = 'SENT'");
        jdbc.update("""
                update command set status = 'FAILED', result_message = '실행 확인 불가 (재시작)'
                 where status = 'ACKED'""");
    }

    private Command map(ResultSet rs, int row) throws SQLException {
        return new Command(
                rs.getLong("id"),
                rs.getLong("satellite_id"),
                CommandType.valueOf(rs.getString("type")),
                read(rs.getString("args")),
                CommandStatus.valueOf(rs.getString("status")),
                rs.getObject("seq", Long.class),
                rs.getInt("attempts"),
                instant(rs, "created_at"),
                instant(rs, "first_sent_at"),
                instant(rs, "last_sent_at"),
                instant(rs, "acked_at"),
                instant(rs, "executed_at"),
                instant(rs, "expires_at"),
                rs.getString("result_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }

    private String write(Map<String, Object> args) {
        try {
            return json.writeValueAsString(args == null ? Map.of() : args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("명령 인자를 JSON으로 변환할 수 없습니다", e);
        }
    }

    private Map<String, Object> read(String args) {
        try {
            return json.readValue(args, ARGS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt command args: " + args, e);
        }
    }
}
