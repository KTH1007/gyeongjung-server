plugins {
    id("gyeongjung.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ops"))
    implementation(project(":collector"))
    implementation("org.springframework.boot:spring-boot-starter")
}
