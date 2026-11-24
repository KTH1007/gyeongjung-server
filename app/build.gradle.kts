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
