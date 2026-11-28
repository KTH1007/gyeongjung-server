package com.gyeongjung;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return isSkipped(root.relativize(dir))
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path relative = root.relativize(file);
                        if (Files.isRegularFile(file)
                                && isScanned(relative)
                                && relativePath.test(relative)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return found;
    }

    // 범위 밖이거나 생성된 폴더는 들어가지 않는다. 걸러내기만 하면 그 안을 모두 훑다가, 접근할 수 없는 폴더 하나에 관련 없는 검사가 깨진다.
    private static boolean isSkipped(Path directory) {
        if (directory.toString().isEmpty()) {
            return false;
        }
        boolean outOfScope = !SCANNED_DIRECTORIES.contains(directory.getName(0).toString());
        return outOfScope || containsGenerated(directory);
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
