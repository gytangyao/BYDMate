-- BydMate sync hub schema.
-- The car head unit upserts into these tables; the phone app only reads.
-- Apply with:  mysql -u bydmate -p bydmate < schema.sql

CREATE DATABASE IF NOT EXISTS bydmate
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- One row per push: the car sends its live dashboard numbers on every sync
-- tick. The phone shows the newest row as "current state".
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS car_state (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  vehicle_id    VARCHAR(64)     NOT NULL,
  pushed_at     BIGINT          NOT NULL COMMENT 'car clock, epoch ms',
  soc_pct       DOUBLE          NULL,
  range_km      DOUBLE          NULL,
  soh_pct       DOUBLE          NULL,
  batt_temp_c   DOUBLE          NULL,
  cell_v_max    DOUBLE          NULL,
  cell_v_min    DOUBLE          NULL,
  voltage_12v   DOUBLE          NULL,
  exterior_temp_c DOUBLE        NULL,
  cabin_temp_c    DOUBLE        NULL,
  odometer_km     DOUBLE        NULL,
  -- Lifetime charging, computed by the car. The phone cannot derive it: it reads at most the last
  -- 200 rows, while the counter spans the whole life of the pack.
  lifetime_charged_kwh DOUBLE   NULL COMMENT 'kWh ever put into the pack: BMS lifetime counter, else the app''s own sum',
  charged_scope   VARCHAR(8)    NULL COMMENT 'which source produced lifetime_charged_kwh: bms | app',
  charge_cycles   DOUBLE        NULL COMMENT 'lifetime_charged_kwh divided by the SoH-corrected capacity below',
  charge_capacity_kwh DOUBLE    NULL COMMENT 'the divisor charge_cycles was actually computed with',
  raw           JSON            NULL COMMENT 'full payload for fields added later',
  PRIMARY KEY (id),
  KEY idx_car_state_vehicle (vehicle_id, pushed_at)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- trips — mirrors BYDMate Room table `trips` (TripEntity).
-- row_version lets the car detect that a finished trip was edited/cost-recalculated.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trips (
  id                 BIGINT UNSIGNED NOT NULL,
  vehicle_id         VARCHAR(64)     NOT NULL,
  start_ts           BIGINT          NOT NULL,
  end_ts             BIGINT          NULL,
  distance_km        DOUBLE          NULL,
  kwh_consumed       DOUBLE          NULL,
  kwh_per_100km      DOUBLE          NULL,
  soc_start          INT             NULL,
  soc_end            INT             NULL,
  temp_avg_c         DOUBLE          NULL,
  avg_speed_kmh      DOUBLE          NULL,
  bat_temp_avg       DOUBLE          NULL,
  bat_temp_max       DOUBLE          NULL,
  bat_temp_min       DOUBLE          NULL,
  cost               DOUBLE          NULL,
  exterior_temp      INT             NULL,
  exterior_temp_end  INT             NULL,
  odometer_start_km  DOUBLE          NULL,
  odometer_end_km    DOUBLE          NULL,
  source             VARCHAR(32)     NULL,
  byd_id             BIGINT          NULL,
  fuel_liters        DOUBLE          NULL,
  hev_km             DOUBLE          NULL,
  fuel_total_start   DOUBLE          NULL,
  fuel_total_end     DOUBLE          NULL,
  mileage_hev_start  DOUBLE          NULL,
  mileage_hev_end    DOUBLE          NULL,
  fuel_cost          DOUBLE          NULL,
  row_version        INT UNSIGNED    NOT NULL DEFAULT 1,
  updated_at         BIGINT          NOT NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_trips_start (vehicle_id, start_ts)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- charges — mirrors BYDMate Room table `charges` (ChargeEntity).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS charges (
  id                     BIGINT UNSIGNED NOT NULL,
  vehicle_id             VARCHAR(64)     NOT NULL,
  start_ts               BIGINT          NOT NULL,
  end_ts                 BIGINT          NULL,
  soc_start              INT             NULL,
  soc_end                INT             NULL,
  kwh_charged            DOUBLE          NULL,
  kwh_charged_soc        DOUBLE          NULL,
  max_power_kw           DOUBLE          NULL,
  avg_power_kw           DOUBLE          NULL,
  type                   VARCHAR(8)      NULL COMMENT 'AC | DC',
  cost                   DOUBLE          NULL,
  lat                    DOUBLE          NULL,
  lon                    DOUBLE          NULL,
  bat_temp_avg           DOUBLE          NULL,
  bat_temp_max           DOUBLE          NULL,
  bat_temp_min           DOUBLE          NULL,
  status                 VARCHAR(16)     NOT NULL DEFAULT 'COMPLETED',
  cell_voltage_min       DOUBLE          NULL,
  cell_voltage_max       DOUBLE          NULL,
  voltage_12v            DOUBLE          NULL,
  exterior_temp          INT             NULL,
  merged_count           INT             NOT NULL DEFAULT 0,
  lifetime_kwh_at_start  DOUBLE          NULL,
  lifetime_kwh_at_finish DOUBLE          NULL,
  gun_state              INT             NULL,
  detection_source       VARCHAR(32)     NULL,
  meter_kwh              DOUBLE          NULL,
  cost_manual            TINYINT(1)      NOT NULL DEFAULT 0,
  row_version            INT UNSIGNED    NOT NULL DEFAULT 1,
  updated_at             BIGINT          NOT NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_charges_start (vehicle_id, start_ts),
  KEY idx_charges_status (vehicle_id, status)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- battery_snapshots — mirrors `battery_snapshots`; the SoH / capacity history.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS battery_snapshots (
  id                   BIGINT UNSIGNED NOT NULL,
  vehicle_id           VARCHAR(64)     NOT NULL,
  `timestamp`          BIGINT          NOT NULL,
  odometer_km          DOUBLE          NULL,
  soc_start            INT             NOT NULL,
  soc_end              INT             NOT NULL,
  kwh_charged          DOUBLE          NOT NULL,
  calculated_capacity_kwh DOUBLE       NULL,
  soh_percent          DOUBLE          NULL,
  cell_delta_v         DOUBLE          NULL,
  bat_temp_avg         DOUBLE          NULL,
  charge_id            BIGINT          NULL,
  row_version          INT UNSIGNED    NOT NULL DEFAULT 1,
  updated_at           BIGINT          NOT NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_battery_ts (vehicle_id, `timestamp`)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Detail tables: the history behind the cards. Each row carries the vehicle id
-- because the phone reads the hub as one flat store; ids are the car's local
-- Room ids, so a re-sent row is an idempotent upsert and not a duplicate.
-- The car prunes all six to the same 90-day ceiling as `car_state`.
-- ---------------------------------------------------------------------------

-- GPS trace of a finished trip, one row per sampled fix. `trip_id` is 0 in
-- Room while the car is still driving; only attached points are shipped.
CREATE TABLE IF NOT EXISTS trip_points (
  id          BIGINT UNSIGNED NOT NULL,
  vehicle_id  VARCHAR(64)     NOT NULL,
  trip_id     BIGINT UNSIGNED NOT NULL,
  ts          BIGINT          NOT NULL COMMENT 'car clock, epoch ms',
  lat         DOUBLE          NOT NULL,
  lon         DOUBLE          NOT NULL,
  speed_kmh   DOUBLE          NULL,
  altitude_m  DOUBLE          NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_trip_points_trip (vehicle_id, trip_id, ts),
  KEY idx_trip_points_ts   (vehicle_id, ts)
) ENGINE = InnoDB;

-- Charging curve of a finished session.
CREATE TABLE IF NOT EXISTS charge_points (
  id          BIGINT UNSIGNED NOT NULL,
  vehicle_id  VARCHAR(64)     NOT NULL,
  charge_id   BIGINT UNSIGNED NOT NULL,
  ts          BIGINT          NOT NULL,
  power_kw    DOUBLE          NULL,
  soc         INT             NULL,
  bat_temp_c  INT             NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_charge_points_charge (vehicle_id, charge_id, ts),
  KEY idx_charge_points_ts     (vehicle_id, ts)
) ENGINE = InnoDB;

-- Parked-with-ignition-on drain sessions. Rows are updated while they run, so
-- the car re-upserts the whole (small) table instead of using a cursor.
CREATE TABLE IF NOT EXISTS idle_drains (
  id            BIGINT UNSIGNED NOT NULL,
  vehicle_id    VARCHAR(64)     NOT NULL,
  start_ts      BIGINT          NOT NULL,
  end_ts        BIGINT          NULL,
  soc_start     INT             NULL,
  soc_end       INT             NULL,
  kwh_consumed  DOUBLE          NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_idle_drains_start (vehicle_id, start_ts)
) ENGINE = InnoDB;

-- Automation journal (the car's local table is named automation_log).
CREATE TABLE IF NOT EXISTS rule_logs (
  id                BIGINT UNSIGNED NOT NULL,
  vehicle_id        VARCHAR(64)     NOT NULL,
  rule_id           BIGINT          NOT NULL,
  rule_name         VARCHAR(200)    NOT NULL,
  triggered_at      BIGINT          NOT NULL,
  triggers_snapshot TEXT            NULL,
  actions_result    TEXT            NULL,
  success           TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_rule_logs_time (vehicle_id, triggered_at)
) ENGINE = InnoDB;

-- Every vehicle write the car attempted, with its readback verdict.
CREATE TABLE IF NOT EXISTS vehicle_write_log (
  id           BIGINT UNSIGNED NOT NULL,
  vehicle_id   VARCHAR(64)     NOT NULL,
  ts           BIGINT          NOT NULL,
  action_name  VARCHAR(64)     NOT NULL,
  dev          INT             NOT NULL,
  fid          INT             NOT NULL,
  requested    INT             NOT NULL,
  readback     INT             NULL,
  status       INT             NOT NULL,
  error        VARCHAR(512)    NULL,
  validated    TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_vehicle_writes_ts (vehicle_id, ts)
) ENGINE = InnoDB;

-- DM-i counter snapshots: the anchors HistoryImporter uses for fuel figures.
CREATE TABLE IF NOT EXISTS counter_snapshots (
  id           BIGINT UNSIGNED NOT NULL,
  vehicle_id   VARCHAR(64)     NOT NULL,
  ts           BIGINT          NOT NULL,
  fuel_total   DOUBLE          NULL,
  mileage_hev  DOUBLE          NULL,
  mileage_ev   DOUBLE          NULL,
  odometer     DOUBLE          NULL,
  reason       VARCHAR(16)     NOT NULL,
  PRIMARY KEY (vehicle_id, id),
  KEY idx_counter_snapshots_ts (vehicle_id, ts)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- app_logs — runtime log lines shipped by BOTH apps, because the head unit cannot
-- be attached to a debugger: this table is the remote diagnosis channel.
-- `source` separates the writer ('car' / 'phone') so one vehicle's two sides never
-- blur together, and `run_id` groups every line from a single process start, which
-- is what makes a crash sequence readable ("what happened right before #42?").
-- Writers append only (id is auto-increment, so a row is never updated); each side
-- dedupes with its own local cursor and enforces its own daily row cap, and both
-- delete their own old rows on a retention pass. Never index `msg`: it is free text
-- up to 2 KB and a FULLTEXT index would make this the biggest table on the NAS.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_logs (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  vehicle_id    VARCHAR(64)     NOT NULL,
  source        VARCHAR(8)      NOT NULL COMMENT 'car | phone',
  ts            BIGINT          NOT NULL COMMENT 'writer clock, epoch ms',
  level         VARCHAR(1)      NOT NULL COMMENT 'V D I W E A F',
  tag           VARCHAR(64)     NOT NULL,
  msg           VARCHAR(2048)   NOT NULL,
  run_id        VARCHAR(32)     NULL COMMENT 'writer process start stamp, one per launch',
  PRIMARY KEY (id),
  KEY idx_logs_window (vehicle_id, source, ts),
  KEY idx_logs_level  (vehicle_id, source, level, ts)
) ENGINE = InnoDB;
