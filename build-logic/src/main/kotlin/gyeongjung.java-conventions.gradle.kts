import com.diffplug.spotless.LineEnding

plugins {
    `java-library`
    checkstyle
    pmd
    id("com.diffplug.spotless")
}

group = "com.gyeongjung"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val springBootBom = platform(libs.findLibrary("spring-boot-dependencies").get())
    val modulithBom = platform(libs.findLibrary("spring-modulith-bom").get())
    implementation(springBootBom)
    implementation(modulithBom)
    annotationProcessor(springBootBom)
    // testFixtures는 implementation 설정을 상속하지 않아서, 이 플러그인을 적용한 모듈에는 BOM을 따로 붙인다
    plugins.withId("java-test-fixtures") {
        "testFixturesImplementation"(springBootBom)
        "testFixturesImplementation"(modulithBom)
    }
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configDirectory = rootProject.layout.projectDirectory.dir("config/checkstyle")
    maxWarnings = 0
}

tasks.withType<Checkstyle>().configureEach {
    // testFixtures도 테스트 코드라서 test와 같은 규칙(한글 메서드 이름 허용 등)을 쓴다
    val isTestCode = name == "checkstyleTest" || name == "checkstyleTestFixtures"
    val suppressions = if (isTestCode) "suppressions-test.xml" else "suppressions-main.xml"
    configProperties = mapOf("suppressions" to suppressions)
}

pmd {
    toolVersion = libs.findVersion("pmd").get().requiredVersion
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("config/pmd/ruleset.xml"))
    sourceSets = listOf(project.sourceSets.main.get())
}

spotless {
    lineEndings = LineEnding.UNIX
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion).aosp()
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    // CI는 UTC인 서버에서 돈다. JVM 시간대가 UTC면 UTC 변환이 깨져도 테스트가 통과하므로 일부러 UTC가 아닌 시간대로 고정한다.
    jvmArgs("-Duser.timezone=Asia/Seoul")
    filter {
        isFailOnNoMatchingTests = false
    }
}
