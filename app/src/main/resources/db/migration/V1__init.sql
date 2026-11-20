CREATE TABLE raw_position_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    train_no         VARCHAR(8)   NOT NULL,
    station_id       VARCHAR(16)  NOT NULL,
    station_name     VARCHAR(32)  NOT NULL,
    train_status     TINYINT      NOT NULL,
    direction        TINYINT      NOT NULL,
    destination_id   VARCHAR(16)  NOT NULL,
    destination_name VARCHAR(32)  NOT NULL,
    express          BOOLEAN      NOT NULL,
    last_car         BOOLEAN      NOT NULL,
    received_at      DATETIME(3)  NOT NULL,
    polled_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_raw_position_event_key (train_no, station_id, train_status, received_at),
    KEY idx_raw_position_event_station_received (station_id, received_at)
);

CREATE TABLE collection_gap (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    started_at DATETIME(3) NOT NULL,
    ended_at   DATETIME(3) NULL,
    reason     VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE api_call_ledger (
    call_date  DATE        NOT NULL,
    calls      INT         NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (call_date)
);
