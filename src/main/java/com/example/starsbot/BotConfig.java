package com.example.starsbot;

public record BotConfig(
        String token,
        String username,
        long ownerId,
        String dbPath,
        int postPriceStars,
        boolean testMode
) {
    public static BotConfig fromEnv() {
        String token = required("BOT_TOKEN");
        String username = required("BOT_USERNAME");
        long ownerId = Long.parseLong(required("BOT_OWNER_ID"));
        String dbPath = getenvOrDefault("BOT_DB_PATH", "./bot.db");
        int postPriceStars = Integer.parseInt(getenvOrDefault("POST_PRICE_STARS", "100"));
        boolean testMode = Boolean.parseBoolean(getenvOrDefault("TEST_MODE", "true"));
        return new BotConfig(token, username, ownerId, dbPath, postPriceStars, testMode);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable is required: " + key);
        }
        return value;
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
