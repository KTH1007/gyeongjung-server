package com.gyeongjung.shared.logging;

import java.util.regex.Pattern;

public final class SecretMasker {

    private static final String MASK = "***";

    // URL 호스트는 대소문자를 구분하지 않아서(서울시 공식 예시도 swopenAPI) 호스트 패턴은 대소문자를 무시한다
    private static final Pattern SEOUL_KEY_IN_PATH =
            Pattern.compile(
                    "(swopenapi\\.seoul\\.go\\.kr/api/[^/\\s\"']+/)[^/\\s\"']+",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_KEY_QUERY =
            Pattern.compile("(serviceKey=)[^&\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCORD_WEBHOOK =
            Pattern.compile(
                    "(discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/)[^\\s\"'<>]+",
                    Pattern.CASE_INSENSITIVE);

    private SecretMasker() {}

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = SEOUL_KEY_IN_PATH.matcher(text).replaceAll("$1" + MASK);
        masked = SERVICE_KEY_QUERY.matcher(masked).replaceAll("$1" + MASK);
        return DISCORD_WEBHOOK.matcher(masked).replaceAll("$1" + MASK);
    }
}
