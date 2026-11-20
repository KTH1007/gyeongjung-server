package com.gyeongjung.shared;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public final class AppTimeZone {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // SMART 해석기는 2026-02-30을 02-28로 조용히 보정하므로 STRICT로 거부한다. STRICT에서는
    // 연도 패턴이 era 없이 해석되지 않아 yyyy 대신 uuuu를 쓴다.
    private static final DateTimeFormatter KST_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

    private AppTimeZone() {}

    public static Instant parseKst(String text) {
        try {
            return LocalDateTime.parse(text, KST_FORMAT).atZone(KST).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Not a KST timestamp (yyyy-MM-dd HH:mm:ss): " + text, e);
        }
    }

    public static ZonedDateTime inKst(Instant instant) {
        return instant.atZone(KST);
    }
}
