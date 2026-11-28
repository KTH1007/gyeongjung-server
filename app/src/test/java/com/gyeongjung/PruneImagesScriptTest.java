package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 같은 서버의 다른 서비스 이미지를 지우면 안 되고, 롤백용 직전 이미지와 실행 중인 이미지도 지우면 안 된다.
// docker를 가짜로 바꿔 끼워서 스크립트가 어떤 이미지를 지우려 하는지만 확인한다.
class PruneImagesScriptTest {

    private static final String SERVER = "ghcr.io/example/gyeongjung-server";
    private static final String BACKUP = "ghcr.io/example/gyeongjung-backup";
    private static final String OURS = "project=gyeongjung";

    // 이미지 목록은 FAKE_IMAGES(저장소, 태그, ID, 만든 시각, 라벨을 탭으로 구분)에서 읽고, 라벨 필터를 받았을 때만 우리 이미지로 거른다.
    // 실행 중인 이미지는 FAKE_IN_USE에서 읽고, 지우라는 요청은 FAKE_CALLS에 기록만 한다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            case "$1 $2" in
              "image ls")
                filtered=0
                for arg in "$@"; do
                  case "$arg" in *label=project=gyeongjung*) filtered=1 ;; esac
                done
                while IFS=$'\\t' read -r repo tag id created labels; do
                  if [ "$filtered" = 1 ] && [ "$labels" != "project=gyeongjung" ]; then continue; fi
                  printf '%s\\t%s\\t%s\\t%s\\n' "$repo" "$tag" "$id" "$created"
                done < "$FAKE_IMAGES"
                ;;
              "ps -a")
                cat "$FAKE_IN_USE"
                ;;
              *)
                if [ "$1" = "rmi" ]; then
                  echo "$*" >> "$FAKE_CALLS"
                  exit 0
                fi
                exit 1
                ;;
            esac
            """;

    @Test
    void 우리_이미지는_저장소마다_최신_2개만_남기고_지운다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = run(temp, images(), "");

        // then
        assertThat(result.exitCode()).isZero();
        // 정확히 이 셋만 지워야 한다. 다른 서비스의 이미지나 -f 옵션이 섞이면 문자열이 달라져 실패한다.
        assertThat(result.calls())
                .containsExactlyInAnyOrder(
                        "rmi " + SERVER + ":c3", "rmi " + SERVER + ":c2", "rmi " + BACKUP + ":b0");
    }

    @Test
    void KEEP_IMAGES가_음이_아닌_정수가_아니면_아무것도_지우지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 정수 비교가 오류로 실패하면 남길 이미지까지 지우게 되므로 값을 먼저 걸러야 한다
        List<String> invalid =
                List.of("two", "-1", "2.5", "1 2", "0x2", "+2", "99999999999999999999");

        // when
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < invalid.size(); i++) {
            Result result =
                    run(
                            temp.resolve("run" + i),
                            Map.of("KEEP_IMAGES", invalid.get(i)),
                            images(),
                            "");
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                wrong.add(
                        invalid.get(i)
                                + " -> 종료 코드 "
                                + result.exitCode()
                                + ", 호출 "
                                + result.calls());
            }
        }

        // then
        assertThat(wrong).as("거르지 못한 값").isEmpty();
    }

    @Test
    void KEEP_IMAGES로_남길_개수를_정하고_0이면_사용_중이_아닌_우리_이미지를_모두_지운다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result none = run(temp.resolve("none"), Map.of("KEEP_IMAGES", "0"), images(), "");
        Result many = run(temp.resolve("many"), Map.of("KEEP_IMAGES", "4"), images(), "");

        // then: 라벨이 붙은 우리 이미지는 7개다(다른 서비스의 이미지는 목록에 오지 않는다)
        assertThat(none.exitCode()).isZero();
        assertThat(none.calls()).hasSize(7).allMatch(call -> call.startsWith("rmi "));
        assertThat(many.exitCode()).isZero();
        assertThat(many.calls()).isEmpty();
    }

    @Test
    void 사용_중인_이미지는_오래되어도_지우지_않는다(@TempDir Path temp) throws IOException, InterruptedException {
        // given: 컨테이너가 c3는 이름으로, c2는 ID로 쓰고 있다
        String inUse = SERVER + ":c3\naaaaaaaaaaa2\n";

        // when
        Result result = run(temp, images(), inUse);

        // then
        assertThat(result.calls()).containsExactly("rmi " + BACKUP + ":b0");
    }

    @Test
    void dry_run이면_아무것도_지우지_않고_지울_이미지를_보여준다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = run(temp, images(), "", "--dry-run");

        // then
        assertThat(result.calls()).isEmpty();
        assertThat(result.output()).contains(SERVER + ":c3", SERVER + ":c2", BACKUP + ":b0");
        assertThat(result.output()).doesNotContain("other-app");
    }

    @Test
    void 같은_이미지에_태그가_여럿이어도_한_개로_세어_직전_이미지를_남긴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 최신 이미지(a5)에 태그가 c5와 latest 둘이다
        String images =
                String.join(
                        "\n",
                        row(SERVER, "c5", "aaaaaaaaaaa5", "2026-11-26 10:00:00 +0000 UTC", OURS),
                        row(
                                SERVER,
                                "latest",
                                "aaaaaaaaaaa5",
                                "2026-11-26 10:00:00 +0000 UTC",
                                OURS),
                        row(SERVER, "c4", "aaaaaaaaaaa4", "2026-11-25 10:00:00 +0000 UTC", OURS),
                        row(SERVER, "c3", "aaaaaaaaaaa3", "2026-11-24 10:00:00 +0000 UTC", OURS));

        // when
        Result result = run(temp, images, "");

        // then
        // 태그를 하나씩 세면 c5와 latest가 "남길 2개"를 채워서 롤백용 직전 이미지(c4)까지 지워진다.
        assertThat(result.calls()).containsExactly("rmi " + SERVER + ":c3");
    }

    @Test
    void 라벨이_붙은_이미지가_없으면_아무것도_지우지_않는다(@TempDir Path temp) throws IOException, InterruptedException {
        // given: 라벨이 없는 이미지만 있고, 우리 것이었다면 지워졌을 만큼 오래된 태그가 있다
        String others =
                String.join(
                        "\n",
                        row(
                                "other-app",
                                "latest",
                                "cccccccccccc",
                                "2026-10-27 10:00:00 +0000 UTC",
                                ""),
                        row("other-app", "v2", "ccccccccccc2", "2026-10-20 10:00:00 +0000 UTC", ""),
                        row("other-app", "v1", "ccccccccccc1", "2026-10-10 10:00:00 +0000 UTC", ""),
                        row(
                                "other-app",
                                "v0",
                                "ccccccccccc0",
                                "2026-10-01 10:00:00 +0000 UTC",
                                ""));

        // when
        Result result = run(temp, others, "");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.calls()).isEmpty();
    }

    // 만든 시각이 섞인 순서로 둔다. 스크립트가 docker가 주는 순서에 기대면 틀린다.
    private static String images() {
        return String.join(
                "\n",
                row(SERVER, "c2", "aaaaaaaaaaa2", "2026-11-23 10:00:00 +0000 UTC", OURS),
                row(SERVER, "c5", "aaaaaaaaaaa5", "2026-11-26 10:00:00 +0000 UTC", OURS),
                row(SERVER, "c3", "aaaaaaaaaaa3", "2026-11-24 10:00:00 +0000 UTC", OURS),
                row(SERVER, "c4", "aaaaaaaaaaa4", "2026-11-25 10:00:00 +0000 UTC", OURS),
                row(BACKUP, "b1", "bbbbbbbbbbb1", "2026-11-20 10:00:00 +0000 UTC", OURS),
                row(BACKUP, "b2", "bbbbbbbbbbb2", "2026-11-26 10:00:00 +0000 UTC", OURS),
                row(BACKUP, "b0", "bbbbbbbbbbb0", "2026-11-10 10:00:00 +0000 UTC", OURS),
                // 라벨이 없는 다른 서비스의 이미지. 우리 것이었다면 지워졌을 만큼 오래된 태그가 있다.
                row("other-app", "latest", "cccccccccccc", "2026-10-27 10:00:00 +0000 UTC", ""),
                row("other-app", "v2", "ccccccccccc2", "2026-10-20 10:00:00 +0000 UTC", ""),
                row("other-app", "v1", "ccccccccccc1", "2026-10-10 10:00:00 +0000 UTC", ""),
                row("other-app", "v0", "ccccccccccc0", "2026-10-01 10:00:00 +0000 UTC", ""),
                row("mysql", "8.0", "eeeeeeeeeeee", "2026-01-01 10:00:00 +0000 UTC", ""),
                row(
                        "prom/prometheus",
                        "latest",
                        "ffffffffffff",
                        "2025-12-01 10:00:00 +0000 UTC",
                        ""));
    }

    private static String row(
            String repository, String tag, String id, String created, String labels) {
        return String.join("\t", repository, tag, id, created, labels);
    }

    private record Result(int exitCode, String output, List<String> calls) {}

    private static Result run(Path temp, String images, String inUse, String... args)
            throws IOException, InterruptedException {
        return run(temp, Map.of(), images, inUse, args);
    }

    private static Result run(
            Path temp, Map<String, String> env, String images, String inUse, String... args)
            throws IOException, InterruptedException {
        Files.createDirectories(temp);
        Path bin = Files.createDirectory(temp.resolve("bin"));
        Path calls = Files.createFile(temp.resolve("calls.txt"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, FAKE_DOCKER);
        assertThat(docker.toFile().setExecutable(true)).as("가짜 docker를 실행 가능하게 만든다").isTrue();
        Files.writeString(temp.resolve("images.tsv"), images + "\n");
        Files.writeString(temp.resolve("in-use.txt"), inUse);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/prune-images.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("FAKE_IMAGES", temp.resolve("images.tsv").toString());
        builder.environment().put("FAKE_IN_USE", temp.resolve("in-use.txt").toString());
        builder.environment().put("FAKE_CALLS", calls.toString());
        // 개발자의 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다.
        builder.environment().remove("KEEP_IMAGES");
        builder.environment().putAll(env);
        ProcessOutput run = ProcessOutput.run(builder, 60);
        assertThat(run.finished()).as("prune-images.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(run.exitCode(), run.output(), Files.readAllLines(calls));
    }
}
