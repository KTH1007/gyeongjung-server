package com.gyeongjung;

import java.nio.file.Files;
import java.nio.file.Path;

// Gradle은 모듈 폴더에서, IDE는 저장소 루트에서 시험을 돌릴 수 있어서 둘 다 찾는다.
final class RepositoryRoot {

    private RepositoryRoot() {}

    static Path path() {
        Path fromModule = Path.of("..");
        return Files.exists(fromModule.resolve("settings.gradle.kts")) ? fromModule : Path.of(".");
    }
}
