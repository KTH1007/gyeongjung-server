package com.gyeongjung.shared;

import jakarta.persistence.Entity;

@Entity
class SampleEntity extends BaseEntity {

    private String name;

    protected SampleEntity() {}

    SampleEntity(String name) {
        this.name = name;
    }

    void rename(String newName) {
        this.name = newName;
    }

    String getName() {
        return name;
    }
}
