package com.example.starsbot;

import com.example.starsbot.model.AdminInfo;
import com.example.starsbot.model.ConversationState;
import com.example.starsbot.model.Draft;
import com.example.starsbot.model.DraftStatus;
import com.example.starsbot.model.MediaType;
import com.example.starsbot.model.PaymentInfo;
import com.example.starsbot.model.UserStateData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Database {
    private static final String TARGET_GROUP_KEY = "target_group_id";
    private static final String TEST_MODE_KEY = "test_mode";
    private static final String POST_PRICE_STARS_KEY = "post_price_stars";
    private final String jdbcUrl;

    public Database(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    public synchronized void init() throws Exception {
        Path dbPath = Path.of(jdbcUrl.replace("jdbc:sqlite:", ""));
        Path parent = dbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Connection conn = open()) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS admins (
                            user_id INTEGER PRIMARY KEY,
                            username TEXT,
                            added_at TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS settings (
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS user_states (
                            user_id INTEGER PRIMARY KEY,
                            state TEXT NOT NULL,
                            draft_id INTEGER,
                            updated_at TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS drafts (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            media_type TEXT,
                            media_file_id TEXT,
                            post_text TEXT,
                            status TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS payments (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            draft_id INTEGER NOT NULL UNIQUE,
                            user_id INTEGER NOT NULL,
                            payer_username TEXT,
                            amount INTEGER NOT NULL,
                            currency TEXT NOT NULL,
                            telegram_payment_charge_id TEXT,
                            provider_payment_charge_id TEXT,
                            status TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """);
            }
        }
    }

    public synchronized void ensureOwnerAdmin(long ownerId) throws SQLException {
        addAdmin(ownerId, null);
    }

    public synchronized void ensureRuntimeDefaults(boolean defaultTestMode, int defaultPostPriceStars) throws SQLException {
        upsertSettingIfAbsent(TEST_MODE_KEY, Boolean.toString(defaultTestMode));
        upsertSettingIfAbsent(POST_PRICE_STARS_KEY, Integer.toString(defaultPostPriceStars));
    }

    public synchronized boolean isAdmin(long userId) throws SQLException {
        String sql = "SELECT 1 FROM admins WHERE user_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void touchAdminUsername(long userId, String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return;
        }
        String sql = "UPDATE admins SET username = ? WHERE user_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public synchronized List<AdminInfo> listAdmins() throws SQLException {
        String sql = "SELECT user_id, username, added_at FROM admins ORDER BY user_id ASC";
        List<AdminInfo> admins = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                admins.add(new AdminInfo(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("added_at")
                ));
            }
        }
        return admins;
    }

    public synchronized void addAdmin(long userId, String username) throws SQLException {
        String sql = """
                INSERT INTO admins(user_id, username, added_at)
                VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                  username = COALESCE(excluded.username, admins.username)
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            if (username == null || username.isBlank()) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, username);
            }
            ps.setString(3, now());
            ps.executeUpdate();
        }
    }

    public synchronized boolean removeAdmin(long userId, long ownerId) throws SQLException {
        if (userId == ownerId) {
            return false;
        }
        String sql = "DELETE FROM admins WHERE user_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void setTargetGroupId(long groupId) throws SQLException {
        upsertSetting(TARGET_GROUP_KEY, Long.toString(groupId));
    }

    public synchronized Optional<Long> getTargetGroupId() throws SQLException {
        Optional<String> raw = getSetting(TARGET_GROUP_KEY);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.get()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public synchronized boolean getTestMode(boolean fallback) throws SQLException {
        Optional<String> raw = getSetting(TEST_MODE_KEY);
        if (raw.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.get());
    }

    public synchronized void setTestMode(boolean enabled) throws SQLException {
        upsertSetting(TEST_MODE_KEY, Boolean.toString(enabled));
    }

    public synchronized int getPostPriceStars(int fallback) throws SQLException {
        Optional<String> raw = getSetting(POST_PRICE_STARS_KEY);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.get());
            if (parsed <= 0) {
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public synchronized void setPostPriceStars(int stars) throws SQLException {
        if (stars <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        upsertSetting(POST_PRICE_STARS_KEY, Integer.toString(stars));
    }

    public synchronized UserStateData getUserState(long userId) throws SQLException {
        String sql = "SELECT state, draft_id FROM user_states WHERE user_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new UserStateData(ConversationState.IDLE, null);
                }
                String stateRaw = rs.getString("state");
                ConversationState state = parseState(stateRaw);
                long draftIdValue = rs.getLong("draft_id");
                Long draftId = rs.wasNull() ? null : draftIdValue;
                return new UserStateData(state, draftId);
            }
        }
    }

    public synchronized void saveUserState(long userId, ConversationState state, Long draftId) throws SQLException {
        String sql = """
                INSERT INTO user_states(user_id, state, draft_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    state = excluded.state,
                    draft_id = excluded.draft_id,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, state.name());
            if (draftId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, draftId);
            }
            ps.setString(4, now());
            ps.executeUpdate();
        }
    }

    public synchronized void clearUserState(long userId) throws SQLException {
        String sql = "DELETE FROM user_states WHERE user_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    public synchronized long createDraft(long userId) throws SQLException {
        String sql = """
                INSERT INTO drafts(user_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            String now = now();
            ps.setLong(1, userId);
            ps.setString(2, DraftStatus.DRAFT.name());
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Could not create draft");
    }

    public synchronized Optional<Draft> getDraft(long draftId) throws SQLException {
        String sql = """
                SELECT id, user_id, media_type, media_file_id, post_text, status
                FROM drafts
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String mediaTypeRaw = rs.getString("media_type");
                MediaType mediaType = mediaTypeRaw == null ? null : MediaType.valueOf(mediaTypeRaw);
                DraftStatus status = DraftStatus.valueOf(rs.getString("status"));
                return Optional.of(new Draft(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        mediaType,
                        rs.getString("media_file_id"),
                        rs.getString("post_text"),
                        status
                ));
            }
        }
    }

    public synchronized void setDraftMedia(long draftId, MediaType mediaType, String mediaFileId) throws SQLException {
        String sql = """
                UPDATE drafts
                SET media_type = ?, media_file_id = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mediaType.name());
            ps.setString(2, mediaFileId);
            ps.setString(3, now());
            ps.setLong(4, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized void setDraftText(long draftId, String text) throws SQLException {
        String sql = """
                UPDATE drafts
                SET post_text = ?, status = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, text);
            ps.setString(2, DraftStatus.READY.name());
            ps.setString(3, now());
            ps.setLong(4, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized void setDraftStatus(long draftId, DraftStatus status) throws SQLException {
        String sql = """
                UPDATE drafts
                SET status = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, now());
            ps.setLong(3, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized void upsertPayment(
            long draftId,
            long userId,
            String payerUsername,
            int amount,
            String currency,
            String telegramPaymentChargeId,
            String providerPaymentChargeId,
            String status
    ) throws SQLException {
        String sql = """
                INSERT INTO payments(
                    draft_id, user_id, payer_username, amount, currency,
                    telegram_payment_charge_id, provider_payment_charge_id, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(draft_id) DO UPDATE SET
                    status = excluded.status,
                    payer_username = COALESCE(excluded.payer_username, payments.payer_username),
                    telegram_payment_charge_id = COALESCE(excluded.telegram_payment_charge_id, payments.telegram_payment_charge_id),
                    provider_payment_charge_id = COALESCE(excluded.provider_payment_charge_id, payments.provider_payment_charge_id)
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            ps.setLong(2, userId);
            if (payerUsername == null || payerUsername.isBlank()) {
                ps.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, payerUsername);
            }
            ps.setInt(4, amount);
            ps.setString(5, currency);
            ps.setString(6, telegramPaymentChargeId);
            ps.setString(7, providerPaymentChargeId);
            ps.setString(8, status);
            ps.setString(9, now());
            ps.executeUpdate();
        }
    }

    public synchronized void updatePaymentStatusByDraft(long draftId, String status) throws SQLException {
        String sql = "UPDATE payments SET status = ? WHERE draft_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized List<PaymentInfo> listRecentPayments(int limit) throws SQLException {
        String sql = """
                SELECT id, draft_id, user_id, payer_username, amount, currency, status, created_at
                FROM payments
                ORDER BY id DESC
                LIMIT ?
                """;
        List<PaymentInfo> payments = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new PaymentInfo(
                            rs.getLong("id"),
                            rs.getLong("draft_id"),
                            rs.getLong("user_id"),
                            rs.getString("payer_username"),
                            rs.getInt("amount"),
                            rs.getString("currency"),
                            rs.getString("status"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return payments;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void upsertSetting(String key, String value) throws SQLException {
        String sql = """
                INSERT INTO settings(key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void upsertSettingIfAbsent(String key, String value) throws SQLException {
        String sql = """
                INSERT INTO settings(key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO NOTHING
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private Optional<String> getSetting(String key) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("value"));
                }
            }
        }
        return Optional.empty();
    }

    private ConversationState parseState(String raw) {
        try {
            return ConversationState.valueOf(raw);
        } catch (Exception ignored) {
            return ConversationState.IDLE;
        }
    }

    private String now() {
        return Instant.now().toString();
    }
}
