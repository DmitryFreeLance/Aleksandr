package com.example.starsbot;

import com.example.starsbot.model.AdminInfo;
import com.example.starsbot.model.AutoPostJob;
import com.example.starsbot.model.ConversationState;
import com.example.starsbot.model.Draft;
import com.example.starsbot.model.DraftMedia;
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
    private static final String TARIFF_2H_KEY = "tariff_2h_price_stars";
    private static final String TARIFF_4H_KEY = "tariff_4h_price_stars";
    private static final String TARIFF_6H_KEY = "tariff_6h_price_stars";
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
                addDraftColumnIfMissing(st, "tariff_interval_hours INTEGER");
                addDraftColumnIfMissing(st, "tariff_price_stars INTEGER");
                addDraftColumnIfMissing(st, "next_publish_at TEXT");
                addDraftColumnIfMissing(st, "publish_until TEXT");
                addDraftColumnIfMissing(st, "publish_count INTEGER NOT NULL DEFAULT 0");
                addDraftColumnIfMissing(st, "last_group_message_id INTEGER");
                addDraftColumnIfMissing(st, "last_group_media_count INTEGER NOT NULL DEFAULT 1");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS draft_media (
                            draft_id INTEGER NOT NULL,
                            position INTEGER NOT NULL,
                            media_type TEXT NOT NULL,
                            file_id TEXT NOT NULL,
                            PRIMARY KEY (draft_id, position),
                            FOREIGN KEY (draft_id) REFERENCES drafts(id) ON DELETE CASCADE
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
        upsertSettingIfAbsent(TARIFF_2H_KEY, "1000");
        upsertSettingIfAbsent(TARIFF_4H_KEY, "700");
        upsertSettingIfAbsent(TARIFF_6H_KEY, "500");
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

    public synchronized int getTariffPrice(int intervalHours, int fallback) throws SQLException {
        Optional<String> raw = getSetting(tariffKey(intervalHours));
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.get());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public synchronized void setTariffPrice(int intervalHours, int stars) throws SQLException {
        if (stars <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        upsertSetting(tariffKey(intervalHours), Integer.toString(stars));
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
                SELECT id, user_id, media_type, media_file_id, post_text, status,
                       tariff_interval_hours, tariff_price_stars, next_publish_at, publish_until, publish_count,
                       last_group_message_id, last_group_media_count
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
                Integer tariffIntervalHours = getNullableInt(rs, "tariff_interval_hours");
                Integer tariffPriceStars = getNullableInt(rs, "tariff_price_stars");
                return Optional.of(new Draft(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        mediaType,
                        rs.getString("media_file_id"),
                        rs.getString("post_text"),
                        status,
                        tariffIntervalHours,
                        tariffPriceStars,
                        rs.getString("next_publish_at"),
                        rs.getString("publish_until"),
                        rs.getInt("publish_count"),
                        getNullableInt(rs, "last_group_message_id"),
                        getNullableInt(rs, "last_group_media_count")
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

    public synchronized void clearDraftMedia(long draftId) throws SQLException {
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM draft_media WHERE draft_id = ?")) {
                ps.setLong(1, draftId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE drafts
                    SET media_type = NULL, media_file_id = NULL, updated_at = ?
                    WHERE id = ?
                    """)) {
                ps.setString(1, now());
                ps.setLong(2, draftId);
                ps.executeUpdate();
            }
        }
    }

    public synchronized int countDraftMedia(long draftId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM draft_media WHERE draft_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public synchronized void addDraftMedia(long draftId, MediaType mediaType, String fileId) throws SQLException {
        int position = countDraftMedia(draftId) + 1;
        String sql = """
                INSERT INTO draft_media(draft_id, position, media_type, file_id)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            ps.setInt(2, position);
            ps.setString(3, mediaType.name());
            ps.setString(4, fileId);
            ps.executeUpdate();
        }
        if (position == 1) {
            setDraftMedia(draftId, mediaType, fileId);
        }
    }

    public synchronized List<DraftMedia> listDraftMedia(long draftId) throws SQLException {
        String sql = """
                SELECT draft_id, position, media_type, file_id
                FROM draft_media
                WHERE draft_id = ?
                ORDER BY position ASC
                """;
        List<DraftMedia> media = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    media.add(new DraftMedia(
                            rs.getLong("draft_id"),
                            rs.getInt("position"),
                            MediaType.valueOf(rs.getString("media_type")),
                            rs.getString("file_id")
                    ));
                }
            }
        }
        if (!media.isEmpty()) {
            return media;
        }

        Optional<Draft> legacy = getDraft(draftId);
        if (legacy.isPresent() && legacy.get().mediaType() != null && legacy.get().mediaFileId() != null) {
            return List.of(new DraftMedia(draftId, 1, legacy.get().mediaType(), legacy.get().mediaFileId()));
        }
        return media;
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

    public synchronized void updateDraftTextKeepStatus(long draftId, String text) throws SQLException {
        String sql = """
                UPDATE drafts
                SET post_text = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, text);
            ps.setString(2, now());
            ps.setLong(3, draftId);
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

    public synchronized void setDraftTariff(long draftId, int intervalHours, int priceStars) throws SQLException {
        String sql = """
                UPDATE drafts
                SET tariff_interval_hours = ?, tariff_price_stars = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, intervalHours);
            ps.setInt(2, priceStars);
            ps.setString(3, now());
            ps.setLong(4, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized void activateAutoPosting(
            long draftId,
            String firstPublishAt,
            String publishUntil,
            Integer lastGroupMessageId,
            Integer lastGroupMediaCount,
            int publishCount
    ) throws SQLException {
        String sql = """
                UPDATE drafts
                SET status = ?, next_publish_at = ?, publish_until = ?, publish_count = ?, last_group_message_id = ?, last_group_media_count = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DraftStatus.ACTIVE.name());
            ps.setString(2, firstPublishAt);
            ps.setString(3, publishUntil);
            ps.setInt(4, publishCount);
            if (lastGroupMessageId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, lastGroupMessageId);
            }
            if (lastGroupMediaCount == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, lastGroupMediaCount);
            }
            ps.setString(7, now());
            ps.setLong(8, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized List<AutoPostJob> listDueAutoPostJobs(String nowIso, int limit) throws SQLException {
        String sql = """
                SELECT id, user_id, media_type, media_file_id, post_text,
                       tariff_interval_hours, next_publish_at, publish_until, publish_count, last_group_message_id, last_group_media_count
                FROM drafts
                WHERE status = ?
                  AND next_publish_at IS NOT NULL
                  AND publish_until IS NOT NULL
                  AND next_publish_at <= ?
                ORDER BY next_publish_at ASC
                LIMIT ?
                """;
        List<AutoPostJob> jobs = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DraftStatus.ACTIVE.name());
            ps.setString(2, nowIso);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mediaTypeRaw = rs.getString("media_type");
                    MediaType mediaType = mediaTypeRaw == null ? null : MediaType.valueOf(mediaTypeRaw);
                    Integer intervalHours = getNullableInt(rs, "tariff_interval_hours");
                    if (mediaType == null || intervalHours == null || intervalHours <= 0) {
                        continue;
                    }
                    jobs.add(new AutoPostJob(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            mediaType,
                            rs.getString("media_file_id"),
                            rs.getString("post_text"),
                            intervalHours,
                            rs.getString("next_publish_at"),
                            rs.getString("publish_until"),
                            rs.getInt("publish_count"),
                            getNullableInt(rs, "last_group_message_id"),
                            getNullableInt(rs, "last_group_media_count")
                    ));
                }
            }
        }
        return jobs;
    }

    public synchronized List<Draft> listUserActiveDrafts(long userId, int limit) throws SQLException {
        String sql = """
                SELECT id, user_id, media_type, media_file_id, post_text, status,
                       tariff_interval_hours, tariff_price_stars, next_publish_at, publish_until, publish_count,
                       last_group_message_id, last_group_media_count
                FROM drafts
                WHERE user_id = ? AND status = ?
                ORDER BY id DESC
                LIMIT ?
                """;
        List<Draft> drafts = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, DraftStatus.ACTIVE.name());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mediaTypeRaw = rs.getString("media_type");
                    MediaType mediaType = mediaTypeRaw == null ? null : MediaType.valueOf(mediaTypeRaw);
                    drafts.add(new Draft(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            mediaType,
                            rs.getString("media_file_id"),
                            rs.getString("post_text"),
                            DraftStatus.valueOf(rs.getString("status")),
                            getNullableInt(rs, "tariff_interval_hours"),
                            getNullableInt(rs, "tariff_price_stars"),
                            rs.getString("next_publish_at"),
                            rs.getString("publish_until"),
                            rs.getInt("publish_count"),
                            getNullableInt(rs, "last_group_message_id"),
                            getNullableInt(rs, "last_group_media_count")
                    ));
                }
            }
        }
        return drafts;
    }

    public synchronized void completeAutoPosting(long draftId, Integer lastGroupMessageId, Integer lastGroupMediaCount, int publishCount) throws SQLException {
        String sql = """
                UPDATE drafts
                SET status = ?, next_publish_at = NULL, publish_count = ?, last_group_message_id = ?, last_group_media_count = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DraftStatus.COMPLETED.name());
            ps.setInt(2, publishCount);
            if (lastGroupMessageId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, lastGroupMessageId);
            }
            if (lastGroupMediaCount == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, lastGroupMediaCount);
            }
            ps.setString(5, now());
            ps.setLong(6, draftId);
            ps.executeUpdate();
        }
    }

    public synchronized void markAutoPostSuccess(long draftId, String nextPublishAt, int newPublishCount, Integer lastGroupMessageId, Integer lastGroupMediaCount) throws SQLException {
        String sql = """
                UPDATE drafts
                SET next_publish_at = ?, publish_count = ?, last_group_message_id = ?, last_group_media_count = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nextPublishAt);
            ps.setInt(2, newPublishCount);
            if (lastGroupMessageId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, lastGroupMessageId);
            }
            if (lastGroupMediaCount == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, lastGroupMediaCount);
            }
            ps.setString(5, now());
            ps.setLong(6, draftId);
            ps.setString(7, DraftStatus.ACTIVE.name());
            ps.executeUpdate();
        }
    }

    public synchronized void markAutoPostFailureRetry(long draftId, String retryAt) throws SQLException {
        String sql = """
                UPDATE drafts
                SET next_publish_at = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, retryAt);
            ps.setString(2, now());
            ps.setLong(3, draftId);
            ps.setString(4, DraftStatus.ACTIVE.name());
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

    public synchronized Optional<String> getPaymentStatusByDraft(long draftId) throws SQLException {
        String sql = "SELECT status FROM payments WHERE draft_id = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, draftId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("status"));
                }
            }
        }
        return Optional.empty();
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

    private void addDraftColumnIfMissing(Statement st, String definition) throws SQLException {
        try {
            st.execute("ALTER TABLE drafts ADD COLUMN " + definition);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!msg.contains("duplicate column name")) {
                throw e;
            }
        }
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String tariffKey(int intervalHours) {
        return switch (intervalHours) {
            case 2 -> TARIFF_2H_KEY;
            case 4 -> TARIFF_4H_KEY;
            case 6 -> TARIFF_6H_KEY;
            default -> throw new IllegalArgumentException("Unsupported tariff interval: " + intervalHours);
        };
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
