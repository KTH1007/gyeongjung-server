package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// sshd의 강제 명령(command="...") 진입점과 배포 락의 약속을 지킨다. 진짜 래퍼와 락 도우미를 임시 폴더에 복사하고
// deploy.sh와 preflight.sh만 가짜로 바꿔 끼운다. 락은 진짜 파일 락이라 동시 실행을 실제로 만들어 확인한다.
class SshDeployScriptTest {

    private static final String REGISTRY = "ghcr.io/example";
    // 형식이 비슷해 보이거나 셸 문법으로 명령을 끼워 넣으려는 시도까지 모두 거부해야 한다.
    private static final List<String> REJECTED_COMMANDS =
            List.of(
                    "",
                    "ls",
                    "deploy",
                    "deploy latest",
                    "deploy abc",
                    "deploy 1234567 extra",
                    "deploy  1234567",
                    " deploy 1234567",
                    "deploy 1234567 ",
                    "deploy 1234567;id",
                    "deploy $(id)",
                    "DEPLOY 1234567",
                    "deploy 1234567\nid",
                    "preflight 1234567 extra",
                    "preflight zzz",
                    "bash -c id",
                    "sudo deploy 1234567",
                    "deploy 1234567 && id",
                    "scp -t /tmp",
                    "internal-sftp");

    private static final Pattern STAMPED =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z .+");

    // 가짜 스크립트는 자기 위치(ops/)의 형제 폴더 state/를 쓴다. 래퍼가 환경변수를 지우므로 환경변수로 받을 수 없다.
    // state/hold 파일이 있는 동안 끝나지 않고, state/exit 파일이 있으면 그 종료 코드로 끝난다.
    private static final String FAKE_DEPLOY =
            """
            #!/bin/bash
            state="$(cd "$(dirname "$0")/../state" && pwd)"
            echo "deploy [$*] registry=${IMAGE_REGISTRY:-}" >> "$state/calls.log"
            env | sort > "$state/deploy-env.txt"
            while [ -e "$state/hold" ]; do sleep 0.1; done
            echo "deploy finished"
            touch "$state/done"
            exit "$(cat "$state/exit" 2> /dev/null || echo 0)"
            """;

    private static final String FAKE_PREFLIGHT =
            """
            #!/bin/bash
            state="$(cd "$(dirname "$0")/../state" && pwd)"
            echo "preflight [$*] registry=${IMAGE_REGISTRY:-}" >> "$state/calls.log"
            exit 0
            """;

    @Test
    void deploy_명령은_태그만_넘겨_deploy_sh를_부르고_종료_코드를_그대로_돌려준다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Files.createDirectories(temp.resolve("state"));
        Files.writeString(temp.resolve("state/exit"), "20");

        // when
        Result result = run(temp, "deploy 2222222");

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(calls(temp)).containsExactly("deploy [2222222] registry=" + REGISTRY);
    }

    @Test
    void 점검_명령은_락을_잡지_않아서_배포가_진행_중이어도_돈다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 배포가 끝나지 않고 붙들려 있다
        hold(temp);
        Process deploy = start(temp, "deploy 1111111");
        awaitCalls(temp, 1);

        // when
        Result withTag = run(temp, "preflight 2222222");
        Result withoutTag = run(temp, "preflight");
        release(temp);
        finish(deploy);

        // then
        assertThat(withTag.exitCode()).isZero();
        assertThat(withoutTag.exitCode()).isZero();
        assertThat(calls(temp))
                .contains(
                        "preflight [2222222] registry=" + REGISTRY,
                        "preflight [] registry=" + REGISTRY);
        assertThat(auditLog(temp)).anyMatch(line -> line.contains("종료 preflight"));
    }

    @Test
    void 허용되지_않은_명령은_아무것도_실행하지_않고_126으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> commands = REJECTED_COMMANDS;

        // when
        List<String> accepted = new ArrayList<>();
        for (String command : commands) {
            Result result = run(temp, command);
            if (result.exitCode() != 126) {
                accepted.add("[" + command + "] 종료 코드 " + result.exitCode());
            }
        }
        Result interactive = run(temp, null);

        // then: 대화형 로그인 시도(명령 없음)도 거부하고, 거부는 명령마다 로그 한 줄이다(줄바꿈이 들어가도 한 줄)
        assertThat(accepted).as("받아들인 명령").isEmpty();
        assertThat(interactive.exitCode()).isEqualTo(126);
        assertThat(calls(temp)).isEmpty();
        assertThat(auditLog(temp))
                .hasSize(commands.size() + 1)
                .allMatch(line -> line.contains("거부"));
    }

    @Test
    void 레지스트리_인자가_올바르지_않으면_아무것도_실행하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> bad =
                List.of(
                        "",
                        "ghcr.io",
                        "Ghcr.io/Owner",
                        "ghcr.io/owner;id",
                        "-x/y",
                        "ghcr.io/owner extra");

        // when
        List<String> accepted = new ArrayList<>();
        for (String registry : bad) {
            Result result = finish(start(temp, "deploy 1234567", registry, Map.of()));
            if (result.exitCode() != 2) {
                accepted.add("[" + registry + "] 종료 코드 " + result.exitCode());
            }
        }
        List<String> callsAfterBad = calls(temp);
        Result good = finish(start(temp, "deploy 1234567", "localhost:5055/gyeongjung", Map.of()));

        // then
        assertThat(accepted).as("받아들인 레지스트리").isEmpty();
        assertThat(callsAfterBad).isEmpty();
        assertThat(good.exitCode()).isZero();
    }

    @Test
    void 배포가_진행_중이면_다음_배포는_기다리지_않고_11로_거부한다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        hold(temp);
        Process first = start(temp, "deploy 1111111");
        awaitCalls(temp, 1);

        // when
        Result second = run(temp, "deploy 2222222");
        release(temp);
        Result firstResult = finish(first);

        // then
        assertThat(second.exitCode()).isEqualTo(11);
        assertThat(second.output()).contains("다른 배포가 진행 중");
        assertThat(firstResult.exitCode()).isZero();
        assertThat(calls(temp)).containsExactly("deploy [1111111] registry=" + REGISTRY);
    }

    @Test
    void 배포가_끝나면_실패했어도_락이_풀려_다음_배포를_받는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Files.createDirectories(temp.resolve("state"));
        Files.writeString(temp.resolve("state/exit"), "20");

        // when
        Result failed = run(temp, "deploy 1111111");
        Files.writeString(temp.resolve("state/exit"), "0");
        Result next = run(temp, "deploy 2222222");

        // then
        assertThat(failed.exitCode()).isEqualTo(20);
        assertThat(next.exitCode()).isZero();
    }

    @Test
    void 락을_잡은_프로세스가_죽어도_배포가_돌고_있는_동안에는_락이_유지되고_끝나면_풀린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        hold(temp);
        Process wrapper = start(temp, "deploy 1111111");
        awaitCalls(temp, 1);
        ProcessHandle holder = ProcessHandle.of(lockHolderPid(temp)).orElseThrow();

        // when: 락을 잡은 프로세스만 죽인다. 배포 프로세스는 계속 돈다
        holder.destroyForcibly();
        await("락 보유 프로세스가 죽을 때까지", () -> !holder.isAlive());
        Result during = run(temp, "deploy 2222222");
        release(temp);
        Result after = untilNotLocked(temp);
        wrapper.waitFor(30, TimeUnit.SECONDS);

        // then
        assertThat(during.exitCode()).isEqualTo(11);
        assertThat(after.exitCode()).isZero();
    }

    @Test
    void 호출마다_감사_로그가_한_줄씩_남고_시작_종료_코드_거부_거절이_기록된다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Files.createDirectories(temp.resolve("state"));
        Files.writeString(temp.resolve("state/exit"), "20");

        // when
        run(temp, "deploy 1111111");
        run(temp, "ls");
        hold(temp);
        Process held = start(temp, "deploy 2222222");
        awaitCalls(temp, 2);
        run(temp, "deploy 3333333");
        release(temp);
        finish(held);

        // then
        List<String> log = auditLog(temp);
        assertThat(log).allMatch(line -> STAMPED.matcher(line).matches());
        assertThat(log.stream().map(line -> line.substring(line.indexOf(' ') + 1)).toList())
                .containsExactly(
                        "요청 deploy 1111111",
                        "종료 deploy 1111111 코드 20",
                        "거부 [ls]",
                        "요청 deploy 2222222",
                        "요청 deploy 3333333",
                        "거절 deploy 3333333 (다른 배포가 진행 중)",
                        "종료 deploy 2222222 코드 20");
    }

    @Test
    void 호출자의_환경변수는_믿지_않고_정해진_값만_배포_스크립트에_넘긴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 시험용 훅과 도커 접속 정보를 호출자 환경에 심는다
        Map<String, String> injected =
                Map.of(
                        "SMOKE_COMMAND", "/tmp/evil",
                        "ROLLING_COMMAND", "/tmp/evil",
                        "PRUNE_COMMAND", "/tmp/evil",
                        "COMPOSE_FILE", "evil.yml",
                        "DOCKER_HOST", "tcp://evil.example:2375",
                        "IMAGE_REGISTRY", "evil.example/x");

        // when
        Result result = finish(start(temp, "deploy 2222222", REGISTRY, injected));

        // then
        List<String> env = Files.readAllLines(temp.resolve("state/deploy-env.txt"));
        assertThat(result.exitCode()).isZero();
        assertThat(env)
                .contains("IMAGE_REGISTRY=" + REGISTRY, "HOME=" + temp.resolve("home"))
                .anyMatch(line -> line.startsWith("PATH="));
        assertThat(String.join("\n", env))
                .doesNotContain("SMOKE_COMMAND", "ROLLING_COMMAND", "PRUNE_COMMAND")
                .doesNotContain("COMPOSE_FILE", "DOCKER_HOST", "evil");
    }

    @Test
    void SSH_연결이_끊겨도_배포는_끝까지_가고_결과가_기록된다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        hold(temp);
        Process process = start(temp, "deploy 1111111");
        awaitCalls(temp, 1);

        // when: 클라이언트가 출력을 받는 쪽을 닫는다(SSH 연결 끊김). 그 뒤에 배포가 출력을 낸다
        process.getInputStream().close();
        release(temp);

        // then
        await("배포가 끝날 때까지", () -> Files.exists(temp.resolve("state/done")));
        await(
                "종료 기록",
                () ->
                        auditLogOrEmpty(temp).stream()
                                .anyMatch(line -> line.contains("종료 deploy 1111111 코드 0")));
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private record Result(int exitCode, String output) {}

    private static Result run(Path temp, String command) throws IOException, InterruptedException {
        return finish(start(temp, command));
    }

    private static Process start(Path temp, String command) throws IOException {
        return start(temp, command, REGISTRY, Map.of());
    }

    // command가 null이면 SSH_ORIGINAL_COMMAND를 아예 설정하지 않는다(대화형 로그인 시도).
    private static Process start(
            Path temp, String command, String registry, Map<String, String> extraEnv)
            throws IOException {
        prepare(temp);
        ProcessBuilder builder =
                new ProcessBuilder("bash", temp.resolve("ops/ssh-deploy.sh").toString(), registry)
                        .redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.remove("SSH_ORIGINAL_COMMAND");
        env.put("HOME", temp.resolve("home").toString());
        if (command != null) {
            env.put("SSH_ORIGINAL_COMMAND", command);
        }
        env.putAll(extraEnv);
        return builder.start();
    }

    private static Result finish(Process process) throws IOException, InterruptedException {
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            // 멈춘 배포가 출력 통로를 붙들고 있으면 읽다가 영원히 멈추므로, 읽기 전에 모두 죽인다.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("래퍼가 30초 안에 끝나야 한다").isTrue();
        return new Result(process.exitValue(), output);
    }

    // 진짜 래퍼와 락 도우미를 복사하고 가짜 스크립트를 놓는다. 이미 있으면 건드리지 않는다(실행 중인 스크립트를 덮어쓰지 않게).
    private static void prepare(Path temp) throws IOException {
        Path ops = Files.createDirectories(temp.resolve("ops"));
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createDirectories(temp.resolve("home"));
        copyIfMissing(
                RepositoryFiles.root().resolve("ops/ssh-deploy.sh"), ops.resolve("ssh-deploy.sh"));
        copyIfMissing(
                RepositoryFiles.root().resolve("ops/lock-run.py"), ops.resolve("lock-run.py"));
        writeIfMissing(ops.resolve("deploy.sh"), FAKE_DEPLOY);
        writeIfMissing(ops.resolve("preflight.sh"), FAKE_PREFLIGHT);
        if (!Files.exists(state.resolve("calls.log"))) {
            Files.createFile(state.resolve("calls.log"));
        }
    }

    private static void copyIfMissing(Path from, Path to) throws IOException {
        if (!Files.exists(to)) {
            Files.copy(from, to);
            assertThat(to.toFile().setExecutable(true)).isTrue();
        }
    }

    private static void writeIfMissing(Path file, String script) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, script);
            assertThat(file.toFile().setExecutable(true)).isTrue();
        }
    }

    private static void hold(Path temp) throws IOException {
        Files.createDirectories(temp.resolve("state"));
        Files.createFile(temp.resolve("state/hold"));
    }

    private static void release(Path temp) throws IOException {
        Files.deleteIfExists(temp.resolve("state/hold"));
    }

    private static List<String> calls(Path temp) throws IOException {
        return Files.readAllLines(temp.resolve("state/calls.log"));
    }

    private static List<String> auditLog(Path temp) throws IOException {
        Path log = temp.resolve("home/.gyeongjung/deploy.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static List<String> auditLogOrEmpty(Path temp) {
        try {
            return auditLog(temp);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long lockHolderPid(Path temp) throws IOException {
        return Long.parseLong(
                Files.readAllLines(temp.resolve("home/.gyeongjung/deploy.lock")).get(0).trim());
    }

    private static void awaitCalls(Path temp, int count) throws InterruptedException {
        await(
                "가짜 배포가 시작될 때까지",
                () -> {
                    try {
                        return calls(temp).size() >= count;
                    } catch (IOException e) {
                        return false;
                    }
                });
    }

    private static Result untilNotLocked(Path temp) throws IOException, InterruptedException {
        Result result = run(temp, "deploy 3333333");
        for (int i = 0; i < 100 && result.exitCode() == 11; i++) {
            Thread.sleep(100);
            result = run(temp, "deploy 3333333");
        }
        return result;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
