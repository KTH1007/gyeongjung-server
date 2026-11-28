package com.gyeongjung;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// 스크립트를 돌려 출력을 받는 시험이 같이 쓴다. 출력을 파이프로 읽으면 자식이 출력 통로를 연 채 멈췄을 때 읽기가 끝나지 않아서
// 제한 시간 대기에 닿지 못하고 CI가 멈춘다. 파일로 받으면 대기가 먼저 일어나고, 출력이 파이프 버퍼보다 커도 자식이 막히지 않는다.
record ProcessOutput(boolean finished, int exitCode, String output) {

    // 제한 시간 안에 끝나지 않으면 자손까지 죽이고, 그때까지의 출력과 finished=false를 돌려준다.
    static ProcessOutput run(ProcessBuilder builder, long limitSeconds)
            throws IOException, InterruptedException {
        Path file = Files.createTempFile("process-output", ".log");
        try {
            builder.redirectOutput(file.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(limitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return new ProcessOutput(finished, finished ? process.exitValue() : -1, output);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
