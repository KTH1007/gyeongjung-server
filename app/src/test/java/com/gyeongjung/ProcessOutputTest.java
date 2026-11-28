package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// 이 도우미가 시간 제한을 지키지 못하면 다른 스크립트 시험이 CI에서 조용히 멈춘다. 그래서 이 시험 자체에도 제한을 건다.
@Timeout(30)
class ProcessOutputTest {

    @Test
    void 끝나면_종료_코드와_표준_출력_표준_오류를_합쳐서_돌려준다() throws IOException, InterruptedException {
        // given
        ProcessBuilder builder =
                new ProcessBuilder("bash", "-c", "echo out; echo err >&2; exit 3")
                        .redirectErrorStream(true);

        // when
        ProcessOutput run = ProcessOutput.run(builder, 10);

        // then
        assertThat(run.finished()).isTrue();
        assertThat(run.exitCode()).isEqualTo(3);
        assertThat(run.output()).contains("out").contains("err");
    }

    @Test
    void 출력이_파이프_버퍼보다_커도_자식이_막히지_않는다() throws IOException, InterruptedException {
        // given: 파이프 버퍼는 보통 64KB다
        ProcessBuilder builder =
                new ProcessBuilder("bash", "-c", "head -c 300000 /dev/zero | tr '\\0' x");

        // when
        ProcessOutput run = ProcessOutput.run(builder, 10);

        // then
        assertThat(run.finished()).isTrue();
        assertThat(run.output()).hasSize(300_000);
    }

    @Test
    void 출력_통로를_연_채_멈추면_제한_시간에_끊고_자손까지_죽인다() throws IOException, InterruptedException {
        // given: 자손이 출력 통로를 물려받아 오래 돈다. 파이프로 읽었다면 여기서 영원히 멈춘다.
        ProcessBuilder builder =
                new ProcessBuilder("bash", "-c", "echo start; sleep 60 & echo $!; wait");

        // when
        long begin = System.nanoTime();
        ProcessOutput run = ProcessOutput.run(builder, 2);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin);

        // then
        assertThat(run.finished()).isFalse();
        assertThat(seconds).as("제한 시간 뒤 바로 돌려줘야 한다").isLessThan(15);
        assertThat(run.output()).startsWith("start\n");
        long grandchild = Long.parseLong(run.output().lines().skip(1).findFirst().orElseThrow());
        assertThat(isGoneSoon(grandchild)).as("자손 프로세스 %d가 정리돼야 한다", grandchild).isTrue();
    }

    private static boolean isGoneSoon(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
