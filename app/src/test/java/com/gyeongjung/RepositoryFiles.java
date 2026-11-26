package com.gyeongjung;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

// 저장소의 문서, 스크립트, Dockerfile을 훑는 시험이 같이 쓴다. 대상은 저장소 루트의 파일과 docs, ops, .github 아래로 한정한다.
// app/build.gradle.kts가 시험의 입력으로 같은 범위를 선언하므로, 범위를 넓히면 거기도 같이 넓힌다.
final class RepositoryFiles {

    private static final Set<String> SCANNED_DIRECTORIES = Set.of("docs", "ops", ".github");
    private static final Set<String> GENERATED_DIRECTORIES =
            Set.of("build", ".gradle", "node_modules");

    private RepositoryFiles() {}

    static Path root() {
        return RepositoryRoot.path().toAbsolutePath().normalize();
    }

    // 저장소 루트 기준 상대 경로가 조건에 맞는 파일을 절대 경로로 돌려준다.
    static List<Path> find(Predicate<Path> relativePath) throws IOException {
        Path root = root();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(RepositoryFiles::isScanned)
                    .filter(relativePath)
                    .map(root::resolve)
                    .toList();
        }
    }

    private static boolean isScanned(Path relative) {
        boolean inScope =
                relative.getNameCount() == 1
                        || SCANNED_DIRECTORIES.contains(relative.getName(0).toString());
        return inScope && !containsGenerated(relative);
    }

    private static boolean containsGenerated(Path relative) {
        for (Path part : relative) {
            if (GENERATED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
