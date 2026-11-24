plugins {
    id("gyeongjung.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ops"))
    implementation(project(":collector"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(testFixtures(project(":shared")))
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.test {
    // 시험이 저장소의 다른 파일을 직접 읽는다. 입력으로 선언하지 않으면 그 파일만 고친 뒤 test가 UP-TO-DATE로 건너뛴다.
    inputs
        .files(
            rootProject.file("docker-compose.yml"),
            rootProject.file("ops/grafana/alerting/rules.yml"),
            rootProject.file("config/checkstyle/import-control.xml"),
        )
        .withPropertyName("repositoryFilesReadByTests")
    // 아직 src가 없는 모듈이 있어서 폴더가 없어도 빈 목록으로 처리되는 fileTree를 쓴다.
    listOf("shared", "ops", "collector", "app").forEach { inputs.files(rootProject.fileTree("$it/src")) }
}
