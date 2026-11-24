plugins {
    id("gyeongjung.java-conventions")
}

dependencies {
    implementation(project(":shared"))
    testImplementation(testFixtures(project(":shared")))
}
