package com.gyeongjung.shared;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mysql.MySQLContainer;

// DB가 필요한 테스트 클래스가 상속한다. 클래스마다 컨테이너를 새로 띄우면 느려서 JVM에 하나만 띄워 모든 테스트가 공유한다.
public abstract class MySqlContainerSupport {

    @ServiceConnection static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static {
        MYSQL.start();
    }
}
