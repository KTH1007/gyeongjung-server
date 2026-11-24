plugins {
    id("gyeongjung.java-conventions")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ops"))
    implementation("jakarta.persistence:jakarta.persistence-api")
    testImplementation(testFixtures(project(":shared")))
}
