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
