package com.gyeongjung.collector.infrastructure;

import com.gyeongjung.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "collection_gap")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CollectionGapEntity extends BaseEntity {

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    CollectionGapEntity(Instant startedAt, String reason) {
        this.startedAt = startedAt;
        this.reason = reason;
    }
}
