package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// 같은 서버의 다른 서비스 이미지와 볼륨까지 지울 수 있는 prune 명령과, 우리 데이터 볼륨을 지우는 down -v가
// 문서나 스크립트에 들어가지 않게 한다.
class NoGlobalPruneTest {

    private static final Pattern PRUNE = Pattern.compile("docker\\s+(\\w+)\\s+prune(.*)");
    private static final Pattern DOWN_WITH_VOLUMES =
            Pattern.compile("\\bdown\\b.*(\\s-\\w*v\\w*\\b|\\s--volumes\\b)");
    private static final Set<String> EXTENSIONS = Set.of(".md", ".sh", ".yml", ".yaml");
    // 일회용 프로젝트(gyeongjung-drill-..., gyeongjung-backup-test)만 지우는 복구 리허설과 시험은 down -v를 쓴다.
    // 이 파일들은 라이브(gyeongjung)와 다른 프로젝트 이름을 직접 지정해야 한다.
    private static final Set<String> THROWAWAY_PROJECT_SCRIPTS =
            Set.of("ops/backup/restore-drill.sh", "ops/backup/test/verify.sh");
    private static final Pattern THROWAWAY_PROJECT = Pattern.compile("-p\\s+gyeongjung-[\\w$]+");

    @Test
    void 서버_전체에_영향을_주는_prune_명령은_쓰지_않는다() throws IOException {
        // given
        List<Path> files = RepositoryFiles.find(NoGlobalPruneTest::isDocumentOrScript);

        // when
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            violations.addAll(violationsIn(file));
        }

        // then
        assertThat(files).as("검사할 문서와 스크립트가 있어야 한다").isNotEmpty();
        assertThat(violations).as("라벨 없이 쓴 prune 명령. 우리 이미지는 ops/prune-images.sh로 정리한다").isEmpty();
    }

    @Test
    void compose_down에_볼륨_삭제_옵션은_일회용_프로젝트만_지우는_파일에서만_쓴다() throws IOException {
        // given
        List<Path> files = RepositoryFiles.find(NoGlobalPruneTest::isDocumentOrScript);

        // when
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            if (!isThrowawayOnly(file)) {
                violations.addAll(linesMatching(file, DOWN_WITH_VOLUMES));
            }
        }

        // then
        assertThat(violations).as("라이브 볼륨까지 지울 수 있는 down -v. 문제가 생기면 컨테이너만 내린다").isEmpty();
    }

    private static boolean isThrowawayOnly(Path file) throws IOException {
        String relative = RepositoryFiles.root().relativize(file).toString();
        return THROWAWAY_PROJECT_SCRIPTS.contains(relative)
                && THROWAWAY_PROJECT.matcher(Files.readString(file)).find();
    }

    private static List<String> linesMatching(Path file, Pattern pattern) throws IOException {
        List<String> matches = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                String where = RepositoryFiles.root().relativize(file) + ":" + (i + 1);
                matches.add(where + " " + lines.get(i).trim());
            }
        }
        return matches;
    }

    private static boolean isDocumentOrScript(Path relative) {
        String name = relative.getFileName().toString();
        return "Dockerfile".equals(name) || EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static List<String> violationsIn(Path file) throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = PRUNE.matcher(lines.get(i));
            if (matcher.find() && !isScoped(matcher.group(1), matcher.group(2))) {
                String where = RepositoryFiles.root().relativize(file) + ":" + (i + 1);
                violations.add(where + " " + lines.get(i).trim());
            }
        }
        return violations;
    }

    // 볼륨과 시스템 전체 정리는 라벨로 좁혀도 위험해서 쓰지 않는다. 이미지와 컨테이너는 우리 라벨로 좁힌 것만 허용한다.
    private static boolean isScoped(String target, String rest) {
        return Set.of("image", "container").contains(target)
                && rest.contains("label=project=gyeongjung");
    }
}
