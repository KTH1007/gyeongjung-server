package com.gyeongjung.shared;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    // DATETIME(3)은 밀리초 아래를 반올림해서 저장하므로(29.9996이 30.000이 된다), 시계가 처음부터
    // 밀리초 단위 시각만 돌려줘서 메모리 값과 DB 값이 달라지지 않게 한다.
    @Bean
    Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }
}
