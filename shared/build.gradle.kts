plugins {
    id("gyeongjung.java-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.data:spring-data-jpa")
    implementation("jakarta.persistence:jakarta.persistence-api")
    implementation("org.slf4j:slf4j-api")
    implementation("ch.qos.logback:logback-classic")
    implementation("org.springframework.boot:spring-boot")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // 다른 모듈의 테스트가 MySqlContainerSupport를 상속하므로 컨테이너 타입과 @ServiceConnection이 그쪽 컴파일에도 보여야 한다
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-mysql")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("com.mysql:mysql-connector-j")
}
