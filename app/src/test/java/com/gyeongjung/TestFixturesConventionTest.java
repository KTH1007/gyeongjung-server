package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// 가짜 시계와 MySQL 컨테이너를 shared의 testFixtures 한 곳에만 두어, 모듈마다 복사본이 생기거나
// 공통 도구를 쓰려고 import-control에 예외를 더하는 일을 막는다.
class TestFixturesConventionTest {

    private static final List<String> MODULES = List.of("shared", "ops", "collector", "app");
    private static final String FIXTURES_DIR = "shared/src/testFixtures/";
    private static final Pattern MYSQL_CONTAINER_DECLARATION =
            Pattern.compile("new\\s+MySQLContainer\\s*\\(");

    @Test
    void MutableClock은_shared의_testFixtures에_한_벌만_있다() throws IOException {
        // given
        List<Path> found =
                javaSources().stream()
                        .filter(path -> path.getFileName().toString().equals("MutableClock.java"))
                        .toList();

        // when & then
        assertThat(found).as("MutableClock 선언 위치").hasSize(1);
        assertThat(normalized(found.get(0))).contains(FIXTURES_DIR);
    }

    @Test
    void MySQL_컨테이너_선언은_shared의_testFixtures_한_곳뿐이다() throws IOException {
        // given
        Map<Path, Long> declarations = new LinkedHashMap<>();
        for (Path source : javaSources()) {
            long count =
                    MYSQL_CONTAINER_DECLARATION.matcher(Files.readString(source)).results().count();
            if (count > 0) {
                declarations.put(source, count);
            }
        }

        // when & then
        assertThat(declarations.values())
                .as("파일별 MySQLContainer 선언 수: %s", declarations)
                .containsExactly(1L);
        assertThat(normalized(declarations.keySet().iterator().next())).contains(FIXTURES_DIR);
    }

    @Test
    void import_control에_AbstractMySqlIT_예외가_없다() throws IOException {
        // given
        String importControl =
                Files.readString(
                        RepositoryRoot.path().resolve("config/checkstyle/import-control.xml"));

        // when & then
        assertThat(importControl).doesNotContain("AbstractMySqlIT");
    }

    private static List<Path> javaSources() throws IOException {
        Path root = RepositoryRoot.path();
        List<Path> sources = new ArrayList<>();
        for (String module : MODULES) {
            Path src = root.resolve(module).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(src)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        return sources;
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}
