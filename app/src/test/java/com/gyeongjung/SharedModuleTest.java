package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class SharedModuleTest {

    @Test
    void shared_모듈은_OPEN으로_선언되어_있다() {
        // given
        ApplicationModules modules = ApplicationModules.of(GyeongjungApplication.class);

        // when
        Optional<ApplicationModule> shared = modules.getModuleByName("shared");

        // then
        assertThat(shared).hasValueSatisfying(module -> assertThat(module.isOpen()).isTrue());
    }
}
