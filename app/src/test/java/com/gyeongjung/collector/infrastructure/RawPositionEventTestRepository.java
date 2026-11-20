package com.gyeongjung.collector.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

interface RawPositionEventTestRepository extends JpaRepository<RawPositionEventEntity, Long> {}
