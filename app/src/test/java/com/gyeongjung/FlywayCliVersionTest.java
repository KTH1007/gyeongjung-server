package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

// 복구 리허설이 앱과 같은 Flyway 버전으로 마이그레이션을 시험해야 하므로, Boot가 Flyway를 올리면 이 시험이 실패해서
// compose의 CLI 이미지도 같이 올리게 한다.
class FlywayCliVersionTest {

    private static final Pattern CLI_IMAGE =
            Pattern.compile("^\\s*image:\\s*flyway/flyway:(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern CORE_JAR = Pattern.compile("flyway-core-(.+)\\.jar");

    @Test
    void compose에_flyway_CLI_이미지가_있다() throws IOException {
        // given
        String compose = Files.readString(composeFile());

        // when
        boolean found = CLI_IMAGE.matcher(compose).find();

        // then
        assertThat(found).as("docker-compose.yml에 flyway/flyway 이미지가 있어야 한다").isTrue();
    }

    @Test
    void compose의_flyway_CLI_버전이_앱의_flyway_core_버전과_같다() throws IOException, URISyntaxException {
        // given
        Matcher cli = CLI_IMAGE.matcher(Files.readString(composeFile()));
        assertThat(cli.find()).as("docker-compose.yml에 flyway/flyway 이미지가 있어야 한다").isTrue();
        String jarName =
                Path.of(Flyway.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .getFileName()
                        .toString();
        Matcher core = CORE_JAR.matcher(jarName);

        // when
        boolean parsed = core.matches();

        // then
        assertThat(parsed).as("flyway-core jar 이름에서 버전을 읽을 수 있어야 한다: %s", jarName).isTrue();
        assertThat(cli.group(1))
                .as(
                        "compose의 flyway CLI(%s)와 앱의 flyway-core(%s)를 같은 버전으로 맞춘다",
                        cli.group(1), core.group(1))
                .isEqualTo(core.group(1));
    }

    // Gradle은 모듈 폴더에서, IDE는 저장소 루트에서 시험을 돌릴 수 있어서 둘 다 찾는다.
    private static Path composeFile() {
        Path fromModule = Path.of("..", "docker-compose.yml");
        return Files.exists(fromModule) ? fromModule : Path.of("docker-compose.yml");
    }
}
