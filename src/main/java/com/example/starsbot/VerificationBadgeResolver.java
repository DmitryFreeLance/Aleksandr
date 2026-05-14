package com.example.starsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

public class VerificationBadgeResolver {
    private static final Logger log = LoggerFactory.getLogger(VerificationBadgeResolver.class);
    private static final String BADGE_REAL = "Проверена. РЕАЛ";
    private static final String BADGE_VIRT = "Проверена. ВИРТ";

    private final String jdbcUrl;
    private final boolean enabled;

    public VerificationBadgeResolver(String verificationDbPath) {
        if (verificationDbPath == null || verificationDbPath.isBlank()) {
            this.jdbcUrl = null;
            this.enabled = false;
            return;
        }
        this.jdbcUrl = "jdbc:sqlite:" + verificationDbPath;
        this.enabled = true;
    }

    public Optional<String> resolveBadge(long userId, String username) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Optional<VerificationType> byId = lookupByUserId(userId);
            Optional<VerificationType> byUsername = lookupByUsername(username);

            // Keep the same priority as in the "real" bot: REAL wins in mixed cases.
            if (byId.isPresent() && byId.get() == VerificationType.REAL) {
                return Optional.of(BADGE_REAL);
            }
            if (byUsername.isPresent() && byUsername.get() == VerificationType.REAL) {
                return Optional.of(BADGE_REAL);
            }
            if (byId.isPresent()) {
                return Optional.of(labelFor(byId.get()));
            }
            if (byUsername.isPresent()) {
                return Optional.of(labelFor(byUsername.get()));
            }
            return Optional.empty();
        } catch (SQLException e) {
            log.warn("Failed to resolve verification badge for user {}", userId, e);
            return Optional.empty();
        }
    }

    private Optional<VerificationType> lookupByUserId(long userId) throws SQLException {
        String sql = "SELECT verification_type FROM verified_users WHERE user_id = ? LIMIT 1";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return parseType(rs.getString("verification_type"));
            }
        }
    }

    private Optional<VerificationType> lookupByUsername(String username) throws SQLException {
        String normalized = normalizeUsername(username);
        if (normalized == null) {
            return Optional.empty();
        }

        String sql = "SELECT verification_type FROM verified_usernames WHERE username = ? LIMIT 1";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return parseType(rs.getString("verification_type"));
            }
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private Optional<VerificationType> parseType(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(VerificationType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String labelFor(VerificationType type) {
        return type == VerificationType.REAL ? BADGE_REAL : BADGE_VIRT;
    }

    private enum VerificationType {
        REAL,
        VIRT
    }
}
