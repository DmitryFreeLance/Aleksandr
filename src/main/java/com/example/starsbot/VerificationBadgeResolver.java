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
    private static final String VERIFICATION_REAL = "REAL";
    private static final String VERIFICATION_VIRT = "VIRT";

    private final String jdbcUrl;
    private final boolean enabled;

    public VerificationBadgeResolver(String verificationDbPath) {
        if (verificationDbPath == null || verificationDbPath.isBlank()) {
            this.enabled = false;
            this.jdbcUrl = null;
        } else {
            this.enabled = true;
            this.jdbcUrl = "jdbc:sqlite:" + verificationDbPath;
        }
    }

    public Optional<String> resolveBadge(long userId, String username) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Optional<String> byId = findByUserId(userId);
            Optional<String> byUsername = findByUsername(username);

            if (byId.isPresent() && VERIFICATION_REAL.equals(byId.get())) {
                return Optional.of(VERIFICATION_REAL);
            }
            if (byUsername.isPresent() && VERIFICATION_REAL.equals(byUsername.get())) {
                return Optional.of(VERIFICATION_REAL);
            }
            if (byId.isPresent()) {
                return byId;
            }
            return byUsername;
        } catch (SQLException e) {
            log.warn("Failed to resolve verification badge for user {}", userId, e);
            return Optional.empty();
        }
    }

    private Optional<String> findByUserId(long userId) throws SQLException {
        String sql = "SELECT verification_type FROM verified_users WHERE user_id = ? LIMIT 1";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(normalizeTypeOrNull(rs.getString("verification_type")));
            }
        }
    }

    private Optional<String> findByUsername(String username) throws SQLException {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername == null) {
            return Optional.empty();
        }
        String sql = "SELECT verification_type FROM verified_usernames WHERE username = ? LIMIT 1";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizedUsername);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(normalizeTypeOrNull(rs.getString("verification_type")));
            }
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
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

    private String normalizeTypeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (VERIFICATION_REAL.equals(normalized) || VERIFICATION_VIRT.equals(normalized)) {
            return normalized;
        }
        return null;
    }
}
