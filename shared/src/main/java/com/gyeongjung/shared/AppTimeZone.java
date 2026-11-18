package com.gyeongjung.shared;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class AppTimeZone {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter KST_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
