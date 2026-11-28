package com.gyeongjung;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 마이그레이션 SQL에서 옛 버전 앱을 깨뜨릴 수 있는 문장을 찾는다. 규칙은 docs/db-migration.md의 규칙 3이다.
// SQL 파서가 아니라 문장과 ALTER 절을 정규식으로 나누는 검사라서, 모르는 문법은 통과시킨다. 걸러내는 쪽이 목적이다.
final class MigrationLint {

    private static final int IGNORE_CASE = Pattern.CASE_INSENSITIVE;
    private static final String NOT_NULL_WITHOUT_DEFAULT = "기본값(DEFAULT) 없는 NOT NULL 컬럼 추가";

    // 문자열과 주석을 한 번에 훑는다. 따로 지우면 주석 안의 작은따옴표나 문자열 안의 --에 속는다.
    private static final Pattern STRING_OR_COMMENT =
            Pattern.compile(
                    "'(?:[^'\\\\]|\\\\.|'')*'|\"(?:[^\"\\\\]|\\\\.)*\"|/\\*.*?\\*/|--[^\\n]*|#[^\\n]*",
                    Pattern.DOTALL);
    // 사유가 같은 줄에 있어야 한다. 사유 앞의 공백이 줄바꿈을 건너뛰면 사유 없는 표시를 인정하게 된다.
    private static final Pattern EXCEPTION =
            Pattern.compile("^[ \\t]*--[ \\t]*compat-ok:[ \\t]*\\S", Pattern.MULTILINE);

    private static final Pattern DROP_TABLE =
            Pattern.compile("^DROP\\s+(?:TABLE|DATABASE|SCHEMA)\\b", IGNORE_CASE);
    private static final Pattern TRUNCATE = Pattern.compile("^TRUNCATE\\b", IGNORE_CASE);
    private static final Pattern RENAME_TABLE = Pattern.compile("^RENAME\\s+TABLE\\b", IGNORE_CASE);
    private static final Pattern ALTER_TABLE =
            Pattern.compile("^ALTER\\s+TABLE\\s+\\S+\\s+(.*)$", IGNORE_CASE);

    // 인덱스, 키, 제약을 가리키는 말이 뒤따르면 컬럼이 아니다. 인덱스를 지우거나 이름을 바꿔도 옛 버전은 돌아간다.
    private static final String NOT_A_COLUMN =
            "(?!(?:INDEX|KEY|UNIQUE|PRIMARY|FOREIGN|CONSTRAINT|CHECK|FULLTEXT|SPATIAL|PARTITION)\\b)";
    private static final Pattern DROP_COLUMN =
            Pattern.compile("^DROP\\s+(?:COLUMN\\s+)?" + NOT_A_COLUMN + "\\w", IGNORE_CASE);
    private static final Pattern RENAME =
            Pattern.compile("^RENAME\\s+(?!(?:INDEX|KEY)\\b)", IGNORE_CASE);
    private static final Pattern CHANGE_COLUMN =
            Pattern.compile("^(?:CHANGE|MODIFY)\\b", IGNORE_CASE);
    private static final Pattern ADD_COLUMNS =
            Pattern.compile("^ADD\\s+(?:COLUMN\\s+)?\\((.*)\\)$", IGNORE_CASE);
    private static final Pattern ADD_COLUMN =
            Pattern.compile("^ADD\\s+" + NOT_A_COLUMN + "(?:COLUMN\\s+)?(.*)$", IGNORE_CASE);
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT\\s+NULL\\b", IGNORE_CASE);
    private static final Pattern DEFAULT_OR_AUTO =
            Pattern.compile("\\b(?:DEFAULT|AUTO_INCREMENT)\\b", IGNORE_CASE);

    private MigrationLint() {}

    // 문장마다 "이유: 문장" 형태로 돌려준다. 사유가 적힌 예외 표시가 있으면 파일 전체를 인정한다.
    static List<String> violations(String sql) {
        if (EXCEPTION.matcher(sql).find()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : withoutStringsAndComments(sql).split(";")) {
            found.addAll(describe(part.replaceAll("\\s+", " ").trim()));
        }
        return found;
    }

    private static List<String> describe(String statement) {
        return reasons(statement).stream().map(reason -> reason + ": " + statement).toList();
    }

    private static String withoutStringsAndComments(String sql) {
        return STRING_OR_COMMENT
                .matcher(sql)
                .replaceAll(match -> isString(match.group()) ? "''" : " ")
                .replace("`", "");
    }

    private static boolean isString(String token) {
        return token.startsWith("'") || token.startsWith("\"");
    }

    private static List<String> reasons(String statement) {
        Matcher alter = ALTER_TABLE.matcher(statement);
        if (!alter.matches()) {
            return wholeStatementReasons(statement);
        }
        List<String> reasons = new ArrayList<>();
        for (String clause : splitTopLevel(alter.group(1))) {
            reasons.addAll(clauseReasons(clause.trim()));
        }
        return reasons;
    }

    private static List<String> wholeStatementReasons(String statement) {
        if (DROP_TABLE.matcher(statement).find()) {
            return List.of("테이블 삭제");
        }
        if (TRUNCATE.matcher(statement).find()) {
            return List.of("테이블 비우기");
        }
        if (RENAME_TABLE.matcher(statement).find()) {
            return List.of("테이블 이름 변경");
        }
        return List.of();
    }

    private static List<String> clauseReasons(String clause) {
        if (DROP_COLUMN.matcher(clause).find()) {
            return List.of("컬럼 삭제");
        }
        if (RENAME.matcher(clause).find()) {
            return List.of("이름 변경");
        }
        if (CHANGE_COLUMN.matcher(clause).find()) {
            return List.of("컬럼 이름이나 타입 변경");
        }
        return addedColumnReasons(clause);
    }

    private static List<String> addedColumnReasons(String clause) {
        Matcher several = ADD_COLUMNS.matcher(clause);
        if (several.matches()) {
            return splitTopLevel(several.group(1)).stream()
                    .filter(MigrationLint::lacksDefault)
                    .map(definition -> NOT_NULL_WITHOUT_DEFAULT)
                    .toList();
        }
        Matcher one = ADD_COLUMN.matcher(clause);
        if (one.matches() && lacksDefault(one.group(1))) {
            return List.of(NOT_NULL_WITHOUT_DEFAULT);
        }
        return List.of();
    }

    // 옛 버전은 새 컬럼을 모른 채 INSERT한다. NOT NULL인데 기본값이 없으면 그 INSERT가 실패한다.
    private static boolean lacksDefault(String definition) {
        return NOT_NULL.matcher(definition).find() && !DEFAULT_OR_AUTO.matcher(definition).find();
    }

    // 괄호 안의 쉼표(DECIMAL(10,2))에서는 자르지 않는다.
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }
}
