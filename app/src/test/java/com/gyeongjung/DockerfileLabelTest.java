package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// 같은 서버에서 우리 이미지만 골라 정리하려면 이미지에 라벨이 있어야 한다. 이름이 비슷한 다른 서비스의 이미지를 지우지 않게 하는 유일한 기준이다.
class DockerfileLabelTest {

    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s");
    private static final Pattern LABEL =
            Pattern.compile("(?m)^LABEL\\s+project=\"?gyeongjung\"?\\s*$");

    @Test
    void 우리가_만드는_모든_Dockerfile의_최종_단계에_project_라벨이_있다() throws IOException {
        // given
        List<Path> dockerfiles =
                RepositoryFiles.find(path -> "Dockerfile".equals(path.getFileName().toString()));

        // when
        List<Path> unlabeled =
                dockerfiles.stream().filter(file -> !hasLabelInFinalStage(file)).toList();

        // then
        // Dockerfile을 하나도 못 찾으면 아래 검사가 아무것도 확인하지 못하고 통과한다.
        assertThat(dockerfiles).hasSizeGreaterThanOrEqualTo(2);
        assertThat(unlabeled).as("최종 단계에 LABEL project=gyeongjung이 없는 Dockerfile").isEmpty();
    }

    // 앞 단계(builder)의 라벨은 최종 이미지에 남지 않으므로 마지막 FROM 뒤만 본다.
    private static boolean hasLabelInFinalStage(Path dockerfile) {
        String text = read(dockerfile);
        Matcher from = FROM.matcher(text);
        int lastFrom = -1;
        while (from.find()) {
            lastFrom = from.start();
        }
        return lastFrom >= 0 && LABEL.matcher(text.substring(lastFrom)).find();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(file + "을 읽지 못했다", e);
        }
    }
}
