package com.gsc.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Satellite findSatellite(long id) {
        return jdbc.queryForObject(
                "select id, name, norad_id, tle_line1, tle_line2 from satellite where id = ?",
                (rs, i) -> new Satellite(rs.getLong("id"), rs.getString("name"), rs.getInt("norad_id"),
                        rs.getString("tle_line1"), rs.getString("tle_line2")),
                id);
    }

    public GroundStation findStation(long id) {
        return jdbc.queryForObject(
                "select id, name, lat_deg, lon_deg, alt_m, min_elevation_deg from ground_station where id = ?",
                (rs, i) -> new GroundStation(rs.getLong("id"), rs.getString("name"), rs.getDouble("lat_deg"),
                        rs.getDouble("lon_deg"), rs.getDouble("alt_m"), rs.getDouble("min_elevation_deg")),
                id);
    }

    public void updateTle(long satelliteId, String line1, String line2) {
        jdbc.update("update satellite set tle_line1 = ?, tle_line2 = ?, tle_updated_at = now() where id = ?",
                line1, line2, satelliteId);
    }
}
