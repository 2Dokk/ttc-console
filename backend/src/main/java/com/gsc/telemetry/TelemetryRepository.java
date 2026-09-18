package com.gsc.telemetry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryRepository {

    private final JdbcTemplate jdbc;

    public TelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(long satelliteId, List<TelemetryPoint> points) {
        jdbc.batchUpdate("""
                insert into telemetry_sample (satellite_id, seq_count, source, recorded_at, received_at,
                    battery_pct, temp_c, roll_deg, pitch_deg, yaw_deg, mode, heater_on, payload_on, sunlit)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", points, points.size(), (ps, p) -> {
                    ps.setLong(1, satelliteId);
                    ps.setLong(2, p.seqCount());
                    ps.setString(3, p.source());
                    ps.setObject(4, utc(p.recordedAt()));
                    ps.setObject(5, utc(p.receivedAt()));
                    ps.setDouble(6, p.batteryPct());
                    ps.setDouble(7, p.tempC());
                    ps.setDouble(8, p.rollDeg());
                    ps.setDouble(9, p.pitchDeg());
                    ps.setDouble(10, p.yawDeg());
                    ps.setString(11, p.mode());
                    ps.setBoolean(12, p.heaterOn());
                    ps.setBoolean(13, p.payloadOn());
                    ps.setBoolean(14, p.sunlit());
                });
    }

    /** Samples taken at or after {@code since}, in spacecraft-time order. */
    public List<TelemetryPoint> findSince(long satelliteId, Instant since, int limit) {
        return jdbc.query("""
                select * from (
                    select * from telemetry_sample
                     where satellite_id = ? and recorded_at >= ?
                     order by recorded_at desc limit ?
                ) latest order by recorded_at""", this::map, satelliteId, utc(since), limit);
    }

    public Optional<Instant> latestRecordedAt() {
        OffsetDateTime latest = jdbc.queryForObject("select max(recorded_at) from telemetry_sample",
                OffsetDateTime.class);
        return Optional.ofNullable(latest).map(OffsetDateTime::toInstant);
    }

    private TelemetryPoint map(ResultSet rs, int row) throws SQLException {
        return new TelemetryPoint(
                rs.getLong("seq_count"),
                rs.getString("source"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getDouble("battery_pct"),
                rs.getDouble("temp_c"),
                rs.getDouble("roll_deg"),
                rs.getDouble("pitch_deg"),
                rs.getDouble("yaw_deg"),
                rs.getString("mode"),
                rs.getBoolean("heater_on"),
                rs.getBoolean("payload_on"),
                rs.getBoolean("sunlit"));
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
