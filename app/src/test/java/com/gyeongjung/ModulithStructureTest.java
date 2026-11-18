package com.gyeongjung;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(GyeongjungApplication.class).verify();
    }
}
