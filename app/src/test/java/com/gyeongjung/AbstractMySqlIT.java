package com.gyeongjung;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
public abstract class AbstractMySqlIT {

    // 클래스마다 컨테이너를 새로 띄우면 느려서 JVM에 하나만 띄워 모든 IT가 공유한다.
    @ServiceConnection static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }
}
