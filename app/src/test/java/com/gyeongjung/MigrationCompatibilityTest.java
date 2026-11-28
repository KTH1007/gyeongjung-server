package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// 롤링 배포 중에는 옛 버전과 새 버전이 같은 DB를 함께 쓴다. 한 번에 하면 옛 버전이 깨지는 변경(docs/db-migration.md 규칙 3)이
// 마이그레이션에 들어오는 것을 막는다. 의도한 3단계 삭제는 파일에 사유가 적힌 예외 표시를 둔다.
class MigrationCompatibilityTest {

    // 이 문장들은 하나씩 모두 걸려야 한다
    private static final Map<String, String> BREAKING =
            Map.ofEntries(
                    Map.entry("테이블 삭제", "DROP TABLE a;"),
                    Map.entry("비우기", "TRUNCATE TABLE a;"),
                    Map.entry("컬럼 삭제", "ALTER TABLE a DROP COLUMN b;"),
                    Map.entry("COLUMN을 생략한 컬럼 삭제", "ALTER TABLE a DROP b;"),
                    Map.entry("여러 절 중 삭제", "ALTER TABLE a ADD COLUMN c INT NULL, DROP COLUMN b;"),
                    Map.entry("테이블 이름 변경", "RENAME TABLE a TO b;"),
                    Map.entry("ALTER로 테이블 이름 변경", "ALTER TABLE a RENAME TO b;"),
                    Map.entry("컬럼 이름 변경", "ALTER TABLE a RENAME COLUMN b TO c;"),
                    Map.entry("CHANGE", "ALTER TABLE a CHANGE b c VARCHAR(10) NULL;"),
                    Map.entry("MODIFY", "ALTER TABLE a MODIFY COLUMN b VARCHAR(5) NULL;"),
                    Map.entry("소문자와 줄바꿈", "alter table a\n  drop\n  column b;"),
                    Map.entry("백틱", "ALTER TABLE `a` DROP COLUMN `b`;"),
                    Map.entry("NOT NULL인데 기본값 없음", "ALTER TABLE a ADD COLUMN b INT NOT NULL;"),
                    Map.entry("COLUMN을 생략한 NOT NULL 추가", "ALTER TABLE a ADD b INT NOT NULL;"),
                    Map.entry(
                            "여러 컬럼 중 하나가 NOT NULL",
                            "ALTER TABLE a ADD COLUMN b INT NULL, ADD COLUMN c VARCHAR(10) NOT NULL;"),
                    Map.entry("타입 괄호 안의 쉼표", "ALTER TABLE a ADD COLUMN b DECIMAL(10,2) NOT NULL;"),
                    Map.entry(
                            "괄호로 묶은 여러 컬럼",
                            "ALTER TABLE a ADD COLUMN (b INT NULL, c INT NOT NULL);"),
                    Map.entry(
                            "기본값이 설명 문자열에만 있음",
                            "ALTER TABLE a ADD COLUMN b INT NOT NULL COMMENT 'DEFAULT 0';"),
                    Map.entry(
                            "기본값이 큰따옴표 설명에만 있음",
                            "ALTER TABLE a ADD COLUMN b INT NOT NULL COMMENT \"DEFAULT 0\";"),
                    Map.entry(
                            "기본값이 블록 주석에만 있음",
                            "ALTER TABLE a ADD COLUMN b INT NOT NULL /* DEFAULT 0 */;"),
                    Map.entry(
                            "기본값이 여러 줄 주석에만 있음",
                            "ALTER TABLE a ADD COLUMN b INT NOT NULL /* 나중에\nDEFAULT 0 */;"),
                    Map.entry(
                            "괄호로 묶은 컬럼 중 기본값이 없는 것이 있음",
                            "ALTER TABLE a ADD COLUMN (b INT NOT NULL DEFAULT 0, c INT NOT NULL);"),
                    Map.entry("백틱과 COLUMN 생략", "ALTER TABLE `a` DROP `b`;"),
                    Map.entry("DATABASE 삭제", "DROP DATABASE a;"),
                    Map.entry("SCHEMA 삭제", "DROP SCHEMA a;"),
                    Map.entry("IF EXISTS가 붙은 테이블 삭제", "DROP TABLE IF EXISTS a;"),
                    Map.entry("한 줄 주석 뒤의 문장", "-- 컬럼 정리\nALTER TABLE a DROP COLUMN b;"),
                    Map.entry("# 주석 뒤의 문장", "# 테이블 정리\nDROP TABLE a;"),
                    Map.entry("블록 주석 뒤의 문장", "/* 테이블 정리 */ DROP TABLE a;"));

    // 이 문장들은 하나도 걸리면 안 된다
    private static final Map<String, String> COMPATIBLE =
            Map.ofEntries(
                    Map.entry(
                            "새 테이블",
                            "CREATE TABLE a (id BIGINT NOT NULL AUTO_INCREMENT, b INT NOT NULL, PRIMARY KEY (id));"),
                    Map.entry("NULL 허용 컬럼 추가", "ALTER TABLE a ADD COLUMN b INT NULL;"),
                    Map.entry(
                            "기본값이 있는 NOT NULL 추가",
                            "ALTER TABLE a ADD COLUMN b INT NOT NULL DEFAULT 0;"),
                    Map.entry(
                            "쉼표가 든 타입과 기본값",
                            "ALTER TABLE a ADD COLUMN b DECIMAL(10,2) NOT NULL DEFAULT 0.00;"),
                    Map.entry(
                            "인덱스 추가",
                            "ALTER TABLE a ADD INDEX idx_b (b), ADD UNIQUE KEY uk_c (c);"),
                    Map.entry(
                            "외래 키 추가",
                            "ALTER TABLE a ADD CONSTRAINT fk FOREIGN KEY (b) REFERENCES c (id);"),
                    Map.entry(
                            "인덱스 삭제와 이름 변경",
                            "ALTER TABLE a DROP INDEX idx_b, RENAME INDEX x TO y;"),
                    Map.entry("데이터 수정", "UPDATE a SET b = 1 WHERE c = 2;"),
                    Map.entry(
                            "작은따옴표 문자열 안의 문장 구분",
                            "INSERT INTO a (b) VALUES ('x; DROP TABLE y; z');"),
                    Map.entry(
                            "큰따옴표 문자열 안의 문장 구분",
                            "INSERT INTO a (b) VALUES (\"x; DROP TABLE y; z\");"),
                    Map.entry(
                            "CHECK 제약 안의 NOT NULL",
                            "ALTER TABLE a ADD CONSTRAINT chk_b CHECK (b IS NOT NULL);"),
                    Map.entry(
                            "AUTO_INCREMENT 컬럼 추가",
                            "ALTER TABLE a ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT, ADD PRIMARY KEY (id);"),
                    Map.entry(
                            "한 줄 주석 속 NOT NULL",
                            "ALTER TABLE a ADD COLUMN b INT NULL -- 나중에 NOT NULL로\n;"),
                    Map.entry(
                            "# 주석 속 NOT NULL",
                            "ALTER TABLE a ADD COLUMN b INT NULL # 나중에 NOT NULL로\n;"),
                    Map.entry(
                            "여러 줄 블록 주석 속 NOT NULL",
                            "ALTER TABLE a ADD COLUMN b INT /* 나중에\nNOT NULL로 */ NULL;"),
                    Map.entry(
                            "주석 안의 키워드",
                            "-- DROP TABLE a;\n/* ALTER TABLE a DROP COLUMN b; */\nCREATE TABLE c (id BIGINT);"));

    @Test
    void 저장소의_마이그레이션은_옛_버전과_호환된다() throws IOException, URISyntaxException {
        // given
        List<Path> files = migrationFiles();

        // when
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            problems.addAll(violationsIn(file));
        }

        // then: 경로를 잘못 잡아 아무것도 검사하지 않는 일을 막으려고 파일이 있는지도 본다
        assertThat(files).as("검사한 마이그레이션 파일").isNotEmpty();
        assertThat(problems).as("옛 버전을 깨뜨릴 수 있는 문장").isEmpty();
    }

    @Test
    void 옛_버전을_깨뜨리는_문장은_하나도_빠짐없이_잡는다() {
        // when
        List<String> missed =
                BREAKING.entrySet().stream()
                        .filter(entry -> MigrationLint.violations(entry.getValue()).isEmpty())
                        .map(Map.Entry::getKey)
                        .toList();

        // then
        assertThat(missed).as("잡지 못한 문장").isEmpty();
    }

    @Test
    void 옛_버전이_견디는_문장은_잡지_않는다() {
        // when
        List<String> wrong =
                COMPATIBLE.entrySet().stream()
                        .filter(entry -> !MigrationLint.violations(entry.getValue()).isEmpty())
                        .map(Map.Entry::getKey)
                        .toList();

        // then
        assertThat(wrong).as("잘못 잡은 문장").isEmpty();
    }

    @Test
    void 문장마다_따로_세고_무엇이_문제인지_알려준다() {
        // given
        String sql =
                "DROP TABLE a;\nALTER TABLE b ADD COLUMN c INT NOT NULL;\nCREATE TABLE d (id BIGINT);";

        // when
        List<String> violations = MigrationLint.violations(sql);

        // then
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("DROP TABLE a");
        assertThat(violations.get(1)).contains("NOT NULL").contains("DEFAULT");
    }

    @Test
    void 호환성_예외는_사유가_적혀_있을_때만_인정한다() {
        // given: 3단계(옛 구조 삭제) 배포의 마이그레이션은 의도한 파괴적 변경이다
        String drop = "ALTER TABLE a DROP COLUMN b;";
        String withReason = "-- compat-ok: v12까지 b를 쓰지 않는 것을 확인함\n" + drop;
        String noReason = "-- compat-ok:\n" + drop;
        String wrongPlace = drop + "\n-- compat-ok 아님: 사유";

        // when / then
        assertThat(MigrationLint.violations(withReason)).isEmpty();
        assertThat(MigrationLint.violations(noReason)).isNotEmpty();
        assertThat(MigrationLint.violations(wrongPlace)).isNotEmpty();
    }

    private static List<String> violationsIn(Path file) throws IOException {
        return MigrationLint.violations(Files.readString(file)).stream()
                .map(violation -> file.getFileName() + ": " + violation)
                .toList();
    }

    private static List<Path> migrationFiles() throws IOException, URISyntaxException {
        URL folder = MigrationCompatibilityTest.class.getClassLoader().getResource("db/migration");
        assertThat(folder).as("클래스패스의 db/migration").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(folder.toURI()))) {
            return files.filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }
}
