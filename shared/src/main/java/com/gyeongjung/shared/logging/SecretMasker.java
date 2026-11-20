package com.gyeongjung.shared.logging;

import java.util.regex.Pattern;

public final class SecretMasker {

    private static final String MASK = "***";

    private static final Pattern SEOUL_KEY_IN_PATH =
            Pattern.compile("(swopenapi\\.seoul\\.go\\.kr/api/[^/\\s\"']+/)[^/\\s\"']+");
    private static final Pattern SERVICE_KEY_QUERY =
            Pattern.compile("(serviceKey=)[^&\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCORD_WEBHOOK =
            Pattern.compile("(discord(?:app)?\\.com/api/webhooks/)[^\\s\"'<>]+");

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
