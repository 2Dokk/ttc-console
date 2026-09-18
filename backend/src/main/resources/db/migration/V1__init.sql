create table satellite (
    id             bigint primary key,
    name           text        not null,
    norad_id       int         not null unique,
    tle_line1      text        not null,
    tle_line2      text        not null,
    tle_updated_at timestamptz not null default now()
);

create table ground_station (
    id                bigint primary key,
    name              text             not null,
    lat_deg           double precision not null,
    lon_deg           double precision not null,
    alt_m             double precision not null,
    min_elevation_deg double precision not null
);

-- All timestamps are mission (simulation) time.
create table command (
    id             bigserial primary key,
    satellite_id   bigint      not null references satellite (id),
    type           text        not null,
    args           text        not null default '{}',
    status         text        not null,
    seq            bigint,
    attempts       int         not null default 0,
    created_at     timestamptz not null,
    first_sent_at  timestamptz,
    last_sent_at   timestamptz,
    acked_at       timestamptz,
    executed_at    timestamptz,
    expires_at     timestamptz,
    result_message text,
    constraint command_status_chk check (status in
        ('PENDING', 'SENT', 'ACKED', 'EXECUTED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'FAILED')),
    constraint command_seq_chk check (seq is not null or status in ('PENDING', 'EXPIRED', 'CANCELLED'))
);

-- A frame sequence number identifies exactly one command per spacecraft.
create unique index command_satellite_seq_uq on command (satellite_id, seq) where seq is not null;
create index command_active_idx on command (satellite_id, status, created_at)
    where status in ('PENDING', 'SENT', 'ACKED');

create table telemetry_sample (
    id           bigserial primary key,
    satellite_id bigint           not null references satellite (id),
    seq_count    bigint           not null,
    source       text             not null check (source in ('REALTIME', 'PLAYBACK')),
    recorded_at  timestamptz      not null,
    received_at  timestamptz      not null,
    battery_pct  double precision not null,
    temp_c       double precision not null,
    roll_deg     double precision not null,
    pitch_deg    double precision not null,
    yaw_deg      double precision not null,
    mode         text             not null,
    heater_on    boolean          not null,
    payload_on   boolean          not null,
    sunlit       boolean          not null
);

create index telemetry_satellite_time_idx on telemetry_sample (satellite_id, recorded_at);

-- Fallback TLEs (refreshed from Celestrak on startup when online).
insert into satellite (id, name, norad_id, tle_line1, tle_line2) values
(1, 'ISS (ZARYA)', 25544,
 '1 25544U 98067A   26261.14280998  .00005718  00000+0  11125-3 0  9991',
 '2 25544  51.6307 200.0361 0004823 152.4527 207.6718 15.49160218586163'),
(2, 'KOMPSAT-3A', 40536,
 '1 40536U 15014A   26261.14411373  .00024094  00000+0  29707-3 0  9996',
 '2 40536  97.6941 259.6824 0049335 192.9038 167.0959 15.58680675636270');

insert into ground_station (id, name, lat_deg, lon_deg, alt_m, min_elevation_deg) values
(1, 'Daejeon GS', 36.3725, 127.3600, 100.0, 5.0);
