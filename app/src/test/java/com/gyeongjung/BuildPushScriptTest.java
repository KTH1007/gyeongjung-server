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

// CI의 ARM 러너가 이미지를 빌드해서 레지스트리에 올리는 스크립트의 약속을 지킨다. docker를 가짜로 바꿔 끼워서
// "무엇을 어떤 순서로 부르는지"만 확인한다. 진짜 빌드와 푸시는 로컬 레지스트리로 따로 시험한다.
class BuildPushScriptTest {

    private static final String REGISTRY = "ghcr.io/example";
    private static final String TAG = "2222222";
    private static final String SERVER = REGISTRY + "/gyeongjung-server:" + TAG;
    private static final String BACKUP = REGISTRY + "/gyeongjung-backup:" + TAG;

    // 호출을 calls.log에 남긴다. FAKE_BUILD_FAIL이나 FAKE_PUSH_FAIL에 든 문자열이 명령에 있으면 그 명령이 실패한다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            echo "docker $*" >> "$FAKE_STATE/calls.log"
            case "$1" in
              build) case "$*" in *"${FAKE_BUILD_FAIL:-no-such-fail}"*) exit 1 ;; esac ;;
              push) case "$*" in *"${FAKE_PUSH_FAIL:-no-such-fail}"*) exit 1 ;; esac ;;
            esac
            exit 0
            """;

    @Test
    void 두_이미지를_모두_빌드한_뒤에_올린다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = run(temp, Map.of(), REGISTRY, TAG);

        // then: 하나라도 빌드에 실패하면 아무것도 올라가지 않게 빌드를 먼저 끝낸다
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls())
                .containsExactly(
                        "docker build --platform linux/arm64 -t " + SERVER + " .",
                        "docker build --platform linux/arm64 -t " + BACKUP + " ops/backup",
                        "docker push " + SERVER,
                        "docker push " + BACKUP);
    }

    @Test
    void 인자가_올바르지_않으면_아무것도_실행하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<List<String>> bad =
                List.of(
                        List.of(REGISTRY, "latest"),
                        List.of(REGISTRY, "abc"),
                        List.of(REGISTRY, "ZZZZZZZ"),
                        List.of(REGISTRY, "1234567;id"),
                        List.of(REGISTRY, "$(id)"),
                        List.of(REGISTRY, ""),
                        List.of("", TAG),
                        List.of("ghcr.io", TAG),
                        List.of("Ghcr.io/Owner", TAG),
                        List.of("ghcr.io/owner;id", TAG),
                        List.of("-x/y", TAG),
                        List.of(REGISTRY),
                        List.<String>of());

        // when
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (List<String> args : bad) {
            Result result = run(temp.resolve("a" + i++), Map.of(), args.toArray(String[]::new));
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                accepted.add(args + " -> 종료 코드 " + result.exitCode());
            }
        }

        // then
        assertThat(accepted).as("받아들인 인자").isEmpty();
    }

    @Test
    void 빌드가_하나라도_실패하면_아무것도_올리지_않고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result serverFails =
                run(
                        temp.resolve("s"),
                        Map.of("FAKE_BUILD_FAIL", "gyeongjung-server"),
                        REGISTRY,
                        TAG);
        Result backupFails =
                run(
                        temp.resolve("b"),
                        Map.of("FAKE_BUILD_FAIL", "gyeongjung-backup"),
                        REGISTRY,
                        TAG);

        // then
        assertThat(serverFails.exitCode()).isEqualTo(10);
        assertThat(serverFails.calls()).hasSize(1);
        assertThat(backupFails.exitCode()).isEqualTo(10);
        assertThat(backupFails.calls())
                .hasSize(2)
                .noneMatch(call -> call.startsWith("docker push"));
    }

    @Test
    void 올리는_도중_실패하면_나머지는_올리지_않고_11로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = run(temp, Map.of("FAKE_PUSH_FAIL", "gyeongjung-server"), REGISTRY, TAG);

        // then
        List<String> pushes =
                result.calls().stream().filter(call -> call.startsWith("docker push")).toList();
        assertThat(result.exitCode()).isEqualTo(11);
        assertThat(pushes).containsExactly("docker push " + SERVER);
    }

    @Test
    void 이미지는_ARM용이고_latest_태그를_만들지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = run(temp, Map.of(), REGISTRY, TAG);

        // then: 서버가 aarch64라서 다른 구조로 만들면 배포 때 실행되지 않는다
        List<String> builds =
                result.calls().stream().filter(call -> call.startsWith("docker build")).toList();
        assertThat(builds).hasSize(2).allMatch(call -> call.contains("--platform linux/arm64"));
        assertThat(result.calls()).noneMatch(call -> call.contains(":latest"));
    }

    private record Result(int exitCode, String output, List<String> calls) {}

    private static Result run(Path temp, Map<String, String> scenario, String... args)
            throws IOException, InterruptedException {
        Path state = prepareFakes(temp);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/build-push.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder.environment(), temp, scenario);

        ProcessOutput run = ProcessOutput.run(builder, 60);
        assertThat(run.finished()).as("build-push.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(
                run.exitCode(), run.output(), Files.readAllLines(state.resolve("calls.log")));
    }

    private static Path prepareFakes(Path temp) throws IOException {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createFile(state.resolve("calls.log"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, FAKE_DOCKER);
        assertThat(docker.toFile().setExecutable(true)).isTrue();
        return state;
    }

    private static void configure(
            Map<String, String> env, Path temp, Map<String, String> scenario) {
        env.keySet().removeIf(name -> name.startsWith("FAKE_"));
        env.put("PATH", temp.resolve("bin") + File.pathSeparator + System.getenv("PATH"));
        env.put("FAKE_STATE", temp.resolve("state").toString());
        env.putAll(scenario);
    }
}
