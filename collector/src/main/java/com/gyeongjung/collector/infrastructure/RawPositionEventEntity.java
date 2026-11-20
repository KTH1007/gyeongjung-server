package com.gyeongjung.collector.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "raw_position_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RawPositionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "train_no", nullable = false, length = 8)
    private String trainNo;

    @Column(name = "station_id", nullable = false, length = 16)
    private String stationId;

    @Column(name = "station_name", nullable = false, length = 32)
    private String stationName;

    @Column(name = "train_status", nullable = false, columnDefinition = "TINYINT")
    private int trainStatus;

    @Column(name = "direction", nullable = false, columnDefinition = "TINYINT")
    private int direction;

    @Column(name = "destination_id", nullable = false, length = 16)
    private String destinationId;

    @Column(name = "destination_name", nullable = false, length = 32)
    private String destinationName;

    @Column(name = "express", nullable = false)
    private boolean express;

    @Column(name = "last_car", nullable = false)
    private boolean lastCar;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "polled_at", nullable = false)
    private Instant polledAt;

    @Builder
    RawPositionEventEntity(
            String trainNo,
            String stationId,
            String stationName,
            int trainStatus,
            int direction,
            String destinationId,
            String destinationName,
            boolean express,
            boolean lastCar,
            Instant receivedAt,
            Instant polledAt) {
        this.trainNo = trainNo;
        this.stationId = stationId;
        this.stationName = stationName;
        this.trainStatus = trainStatus;
        this.direction = direction;
        this.destinationId = destinationId;
        this.destinationName = destinationName;
        this.express = express;
        this.lastCar = lastCar;
        this.receivedAt = receivedAt;
        this.polledAt = polledAt;
    }
}
