package com.gyeongjung.shared;

import org.springframework.data.jpa.repository.JpaRepository;

interface SampleRepository extends JpaRepository<SampleEntity, Long> {}
