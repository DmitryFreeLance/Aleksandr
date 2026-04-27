package com.example.starsbot;

import com.example.starsbot.model.AdminInfo;
import com.example.starsbot.model.AutoPostJob;
import com.example.starsbot.model.ConversationState;
import com.example.starsbot.model.Draft;
import com.example.starsbot.model.DraftStatus;
import com.example.starsbot.model.MediaType;
import com.example.starsbot.model.PaymentInfo;
import com.example.starsbot.model.UserStateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StarsPostBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(StarsPostBot.class);
    private static final String CURRENCY_STARS = "XTR";
    private static final int MAX_CAPTION_LENGTH = 1024;
    private static final Pattern PAYLOAD_PATTERN = Pattern.compile("^draft:(\\d+)(?::(\\d+))?$");
    private static final Duration CAMPAIGN_DURATION = Duration.ofDays(7);
    private static final Duration FAILED_RETRY_DELAY = Duration.ofMinutes(10);

    private static final String CB_MAKE_POST = "make_post";
    private static final String CB_ADMIN_MENU = "admin_menu";
    private static final String CB_ADMIN_BACK = "admin_back";
    private static final String CB_ADMIN_SET_GROUP = "admin_set_group";
    private static final String CB_ADMIN_LAST_PAYMENTS = "admin_last_payments";
    private static final String CB_ADMIN_LIST_ADMINS = "admin_list_admins";
    private static final String CB_ADMIN_ADD_ADMIN = "admin_add_admin";
    private static final String CB_ADMIN_REMOVE_MENU = "admin_remove_menu";
    private static final String CB_ADMIN_TOGGLE_TEST_MODE = "admin_toggle_test_mode";
    private static final String CB_ADMIN_EDIT_PRICES = "admin_edit_prices";
    private static final String CB_ADMIN_EDIT_PRICE_PREFIX = "admin_edit_price:";
    private static final String CB_CANCEL_FLOW = "cancel_flow";
    private static final String CB_TARIFF_PREFIX = "tariff:";
    private static final String CB_TARIFF_MENU_PREFIX = "tariff_menu:";
    private static final String PAY_STATUS_RUNNING = "AUTOPOST_RUNNING";
    private static final String PAY_STATUS_COMPLETED = "AUTOPOST_COMPLETED";
    private static final String PAY_STATUS_RUNNING_TEST = "AUTOPOST_RUNNING_TEST";
    private static final String PAY_STATUS_COMPLETED_TEST = "AUTOPOST_COMPLETED_TEST";

    private final BotConfig config;
    private final Database database;
    private final ScheduledExecutorService autoPostExecutor;

    public StarsPostBot(BotConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.autoPostExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "autopost-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.autoPostExecutor.scheduleWithFixedDelay(this::safeAutoPostTick, 20, 30, TimeUnit.SECONDS);
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                handlePreCheckout(update.getPreCheckoutQuery());
                return;
            }

            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }

            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to process update", e);
        }
    }

    @Override
    public String getBotUsername() {
        return config.username();
    }

    @Override
    public String getBotToken() {
        return config.token();
    }

    private void handleMessage(Message message) throws Exception {
        if (message.getFrom() == null) {
            return;
        }

        long userId = message.getFrom().getId();
        String username = message.getFrom().getUserName();
        if (database.isAdmin(userId)) {
            database.touchAdminUsername(userId, username);
        }

        if (!isPrivateMessage(message)) {
            return;
        }

        if (message.hasSuccessfulPayment()) {
            handleSuccessfulPayment(message);
            return;
        }

        if (message.hasText()) {
            String text = message.getText().trim();
            if ("/start".equalsIgnoreCase(text)) {
                sendMainMenu(message.getChatId(), userId);
                return;
            }
        }

        UserStateData userState = database.getUserState(userId);
        switch (userState.state()) {
            case WAIT_MEDIA -> handleMediaInput(message, userState);
            case WAIT_TEXT -> handleTextInput(message, userState);
            case WAIT_TARIFF -> handleTariffHint(message, userState);
            case WAIT_GROUP_ID -> handleGroupInput(message, userId);
            case WAIT_NEW_ADMIN_ID -> handleNewAdminInput(message, userId);
            case WAIT_TARIFF_PRICE -> handleTariffPriceInput(message, userState, userId);
            default -> {
                if (message.hasText() && "/admin".equalsIgnoreCase(message.getText().trim()) && database.isAdmin(userId)) {
                    sendAdminPanel(message.getChatId());
                    return;
                }
                sendMainMenu(message.getChatId(), userId);
            }
        }
    }

    private void handleCallback(CallbackQuery query) throws Exception {
        User from = query.getFrom();
        long userId = from.getId();
        if (database.isAdmin(userId)) {
            database.touchAdminUsername(userId, from.getUserName());
        }

        if (!isPrivateCallback(query)) {
            return;
        }

        String data = query.getData();
        if (data == null) {
            answerCallback(query.getId(), null, false);
            return;
        }

        if (CB_MAKE_POST.equals(data)) {
            startPostFlow(query, true);
            return;
        }
        if (CB_ADMIN_MENU.equals(data)) {
            if (!database.isAdmin(userId)) {
                answerCallback(query.getId(), "Недостаточно прав", true);
                return;
            }
            database.clearUserState(userId);
            sendAdminPanel(query.getMessage().getChatId());
            answerCallback(query.getId(), null, false);
            return;
        }
        if (CB_ADMIN_BACK.equals(data)) {
            if (!database.isAdmin(userId)) {
                answerCallback(query.getId(), "Недостаточно прав", true);
                return;
            }
            database.clearUserState(userId);
            sendAdminPanel(query.getMessage().getChatId());
            answerCallback(query.getId(), null, false);
            return;
        }
        if (data.startsWith(CB_TARIFF_PREFIX)) {
            applyTariff(query, data);
            return;
        }
        if (data.startsWith(CB_TARIFF_MENU_PREFIX)) {
            long draftId = parseIdSuffix(data, CB_TARIFF_MENU_PREFIX);
            showTariffMenu(query, draftId);
            return;
        }
        if (data.startsWith("pay:")) {
            long draftId = parseIdSuffix(data, "pay:");
            sendInvoice(query, draftId);
            return;
        }
        if (data.startsWith("restart:")) {
            cancelDraftFlow(query, parseIdSuffix(data, "restart:"), true);
            return;
        }
        if (CB_CANCEL_FLOW.equals(data)) {
            cancelDraftFlow(query, null, false);
            return;
        }
        if (CB_ADMIN_SET_GROUP.equals(data)) {
            adminSetGroupMode(query);
            return;
        }
        if (CB_ADMIN_LAST_PAYMENTS.equals(data)) {
            adminShowLastPayments(query);
            return;
        }
        if (CB_ADMIN_LIST_ADMINS.equals(data)) {
            adminShowAdmins(query);
            return;
        }
        if (CB_ADMIN_ADD_ADMIN.equals(data)) {
            adminAddMode(query);
            return;
        }
        if (CB_ADMIN_REMOVE_MENU.equals(data)) {
            adminRemoveMenu(query);
            return;
        }
        if (CB_ADMIN_TOGGLE_TEST_MODE.equals(data)) {
            adminToggleTestMode(query);
            return;
        }
        if (CB_ADMIN_EDIT_PRICES.equals(data)) {
            adminShowTariffPriceMenu(query);
            return;
        }
        if (data.startsWith(CB_ADMIN_EDIT_PRICE_PREFIX)) {
            adminSetTariffPriceMode(query, parseIdSuffix(data, CB_ADMIN_EDIT_PRICE_PREFIX));
            return;
        }
        if (data.startsWith("admin_remove:")) {
            long adminIdToRemove = parseIdSuffix(data, "admin_remove:");
            adminRemove(query, adminIdToRemove);
            return;
        }

        answerCallback(query.getId(), null, false);
    }

    private void startPostFlow(CallbackQuery query, boolean answerCallback) throws Exception {
        long userId = query.getFrom().getId();
        long chatId = query.getMessage().getChatId();

        if (database.getTargetGroupId().isEmpty()) {
            if (answerCallback) {
                answerCallback(query.getId(), "Публикация пока недоступна", true);
            }
            sendText(chatId, """
                    Сейчас группа для публикаций не настроена.
                    Попросите администратора открыть панель и задать ID группы.
                    """, mainMenuKeyboard(database.isAdmin(userId)));
            return;
        }

        long draftId = database.createDraft(userId);
        database.saveUserState(userId, ConversationState.WAIT_MEDIA, draftId);

        if (answerCallback) {
            answerCallback(query.getId(), null, false);
        }
        boolean testMode = isTestModeEnabled();
        String modeNotice = testMode ? "\n🧪 Сейчас включён тестовый режим: списания не реальные." : "";
        sendText(chatId, """
                📝 Создаем автопост на 7 дней.

                Тарифы:
                %s

                Сначала пришлите одно фото или одно видео.%s
                """.formatted(formatTariffLinesForText(), modeNotice), inlineKeyboard(
                row(button("❌ Отмена", CB_CANCEL_FLOW))
        ));
    }

    private void handleMediaInput(Message message, UserStateData state) throws Exception {
        long userId = message.getFrom().getId();
        Long draftId = state.draftId();
        if (draftId == null) {
            database.clearUserState(userId);
            sendMainMenu(message.getChatId(), userId);
            return;
        }

        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            PhotoSize best = photos.get(photos.size() - 1);
            database.setDraftMedia(draftId, MediaType.PHOTO, best.getFileId());
            database.saveUserState(userId, ConversationState.WAIT_TEXT, draftId);
            sendText(message.getChatId(), """
                    Отлично, медиа получено ✅
                    Теперь отправьте текст поста (до 1024 символов).
                    """, inlineKeyboard(row(button("❌ Отмена", CB_CANCEL_FLOW))));
            return;
        }

        if (message.hasVideo()) {
            database.setDraftMedia(draftId, MediaType.VIDEO, message.getVideo().getFileId());
            database.saveUserState(userId, ConversationState.WAIT_TEXT, draftId);
            sendText(message.getChatId(), """
                    Видео принято ✅
                    Теперь отправьте текст поста (до 1024 символов).
                    """, inlineKeyboard(row(button("❌ Отмена", CB_CANCEL_FLOW))));
            return;
        }

        sendText(message.getChatId(), """
                Нужен именно один файл: фото или видео.
                Пришлите медиа, чтобы продолжить.
                """, inlineKeyboard(row(button("❌ Отмена", CB_CANCEL_FLOW))));
    }

    private void handleTextInput(Message message, UserStateData state) throws Exception {
        long userId = message.getFrom().getId();
        Long draftId = state.draftId();
        if (draftId == null) {
            database.clearUserState(userId);
            sendMainMenu(message.getChatId(), userId);
            return;
        }

        if (!message.hasText() || message.getText().isBlank()) {
            sendText(message.getChatId(), "Отправьте текст поста обычным сообщением.", inlineKeyboard(row(button("❌ Отмена", CB_CANCEL_FLOW))));
            return;
        }

        String text = message.getText().trim();
        if (text.length() > MAX_CAPTION_LENGTH) {
            sendText(message.getChatId(), "Текст слишком длинный. Лимит: 1024 символа.", inlineKeyboard(row(button("❌ Отмена", CB_CANCEL_FLOW))));
            return;
        }

        database.setDraftText(draftId, text);
        database.saveUserState(userId, ConversationState.WAIT_TARIFF, draftId);

        sendText(message.getChatId(), """
                Текст сохранен ✨
                Теперь выберите тариф автопостинга на 7 дней:
                """, tariffKeyboard(draftId, true));
    }

    private void handleTariffHint(Message message, UserStateData state) throws Exception {
        Long draftId = state.draftId();
        if (draftId == null) {
            sendMainMenu(message.getChatId(), message.getFrom().getId());
            return;
        }
        sendText(message.getChatId(), """
                Выберите тариф кнопкой ниже:
                """, tariffKeyboard(draftId, true));
    }

    private void applyTariff(CallbackQuery query, String callbackData) throws Exception {
        long userId = query.getFrom().getId();
        String[] parts = callbackData.split(":");
        if (parts.length != 3) {
            answerCallback(query.getId(), "Некорректный тариф", true);
            return;
        }

        int intervalHours;
        long draftId;
        try {
            intervalHours = Integer.parseInt(parts[1]);
            draftId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            answerCallback(query.getId(), "Некорректный тариф", true);
            return;
        }

        TariffPlan tariff = tariffByInterval(intervalHours);
        if (tariff == null) {
            answerCallback(query.getId(), "Тариф не найден", true);
            return;
        }

        Optional<Draft> draftOpt = database.getDraft(draftId);
        if (draftOpt.isEmpty()) {
            answerCallback(query.getId(), "Черновик не найден", true);
            return;
        }

        Draft draft = draftOpt.get();
        if (draft.userId() != userId) {
            answerCallback(query.getId(), "Это не ваш черновик", true);
            return;
        }
        if (draft.status() != DraftStatus.READY) {
            answerCallback(query.getId(), "Черновик недоступен", true);
            return;
        }
        if (draft.mediaType() == null || draft.mediaFileId() == null || draft.postText() == null || draft.postText().isBlank()) {
            answerCallback(query.getId(), "Черновик не заполнен полностью", true);
            return;
        }

        database.setDraftTariff(draftId, tariff.intervalHours(), tariff.priceStars());
        database.saveUserState(userId, ConversationState.WAIT_TARIFF, draftId);

        answerCallback(query.getId(), "Тариф выбран", false);
        sendText(query.getMessage().getChatId(), """
                ✅ Тариф: каждые %d часа(ов), 7 дней.
                Стоимость: <b>%d Stars</b>.

                Проверьте предпросмотр и нажмите «Оплатить».
                """.formatted(tariff.intervalHours(), tariff.priceStars()), null);
        sendPreviewMedia(userId, draft, paymentKeyboard(draftId, tariff));
    }

    private InlineKeyboardMarkup tariffKeyboard(long draftId, boolean includeRestart) {
        TariffPlan t2 = tariffByInterval(2);
        TariffPlan t4 = tariffByInterval(4);
        TariffPlan t6 = tariffByInterval(6);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(button("⏱ Каждые 2 часа — " + t2.priceStars() + " ⭐", CB_TARIFF_PREFIX + "2:" + draftId)));
        rows.add(row(button("⏱ Каждые 4 часа — " + t4.priceStars() + " ⭐", CB_TARIFF_PREFIX + "4:" + draftId)));
        rows.add(row(button("⏱ Каждые 6 часов — " + t6.priceStars() + " ⭐", CB_TARIFF_PREFIX + "6:" + draftId)));
        if (includeRestart) {
            rows.add(row(button("🔄 Начать заново", "restart:" + draftId)));
        }
        rows.add(row(button("❌ Отмена", CB_CANCEL_FLOW)));
        return inlineKeyboard(rows.toArray(List[]::new));
    }

    private InlineKeyboardMarkup paymentKeyboard(long draftId, TariffPlan tariff) throws SQLException {
        boolean testMode = isTestModeEnabled();
        String payLabel = testMode
                ? "🧪 Тестовая оплата (" + tariff.priceStars() + " Stars)"
                : "💫 Оплатить " + tariff.priceStars() + " Stars";
        return inlineKeyboard(
                row(button(payLabel, "pay:" + draftId)),
                row(button("🔁 Выбрать другой тариф", CB_TARIFF_MENU_PREFIX + draftId)),
                row(button("🔄 Начать заново", "restart:" + draftId))
        );
    }

    private void showTariffMenu(CallbackQuery query, long draftId) throws Exception {
        long userId = query.getFrom().getId();
        Optional<Draft> draftOpt = database.getDraft(draftId);
        if (draftOpt.isEmpty() || draftOpt.get().userId() != userId) {
            answerCallback(query.getId(), "Черновик не найден", true);
            return;
        }
        if (draftOpt.get().status() != DraftStatus.READY) {
            answerCallback(query.getId(), "Черновик недоступен", true);
            return;
        }
        answerCallback(query.getId(), null, false);
        sendText(query.getMessage().getChatId(), "Выберите новый тариф:", tariffKeyboard(draftId, false));
    }

    private void sendInvoice(CallbackQuery query, long draftId) throws Exception {
        long userId = query.getFrom().getId();
        Optional<Draft> draftOpt = database.getDraft(draftId);
        if (draftOpt.isEmpty()) {
            answerCallback(query.getId(), "Черновик не найден", true);
            return;
        }
        Draft draft = draftOpt.get();
        if (draft.userId() != userId) {
            answerCallback(query.getId(), "Это не ваш черновик", true);
            return;
        }
        if (draft.status() == DraftStatus.PAID || draft.status() == DraftStatus.ACTIVE || draft.status() == DraftStatus.PUBLISHED || draft.status() == DraftStatus.COMPLETED) {
            answerCallback(query.getId(), "Кампания уже оплачена", true);
            return;
        }
        if (database.getTargetGroupId().isEmpty()) {
            answerCallback(query.getId(), "Группа не настроена администратором", true);
            return;
        }
        TariffPlan tariff = tariffFromDraft(draft);
        if (tariff == null) {
            answerCallback(query.getId(), "Сначала выберите тариф", true);
            return;
        }
        int postPrice = tariff.priceStars();
        if (isTestModeEnabled()) {
            answerCallback(query.getId(), "Тестовая оплата выполнена", false);
            simulatePayment(query, draft, tariff);
            return;
        }

        StarsSendInvoice invoice = new StarsSendInvoice();
        invoice.setChatId(String.valueOf(userId));
        invoice.setTitle("Публикация рекламного поста");
        invoice.setDescription("Моментальная публикация вашего поста в группе.");
        invoice.setPayload(buildPayload(draftId, postPrice));
        invoice.setProviderToken("");
        invoice.setCurrency(CURRENCY_STARS);
        invoice.setPrices(List.of(new LabeledPrice("Размещение поста", postPrice)));
        invoice.setStartParameter("new-post");

        execute(invoice);
        answerCallback(query.getId(), "Счёт отправлен", false);
    }

    private void simulatePayment(CallbackQuery query, Draft draft, TariffPlan tariff) throws Exception {
        long userId = query.getFrom().getId();
        completePaidDraft(
                draft,
                userId,
                query.getMessage().getChatId(),
                query.getFrom().getUserName(),
                tariff.priceStars(),
                CURRENCY_STARS,
                "TEST-" + draft.id() + "-" + System.currentTimeMillis(),
                "TEST",
                "PAID_TEST",
                true
        );
    }

    private void handlePreCheckout(PreCheckoutQuery preCheckoutQuery) throws Exception {
        String error = validatePayment(preCheckoutQuery);
        AnswerPreCheckoutQuery answer = new AnswerPreCheckoutQuery();
        answer.setPreCheckoutQueryId(preCheckoutQuery.getId());
        if (error == null) {
            answer.setOk(true);
        } else {
            answer.setOk(false);
            answer.setErrorMessage(error);
        }
        execute(answer);
    }

    private String validatePayment(PreCheckoutQuery preCheckoutQuery) throws SQLException {
        if (!CURRENCY_STARS.equals(preCheckoutQuery.getCurrency())) {
            return "Неверная валюта платежа.";
        }
        InvoicePayload payload = parsePayload(preCheckoutQuery.getInvoicePayload());
        if (payload == null) {
            return "Некорректный платежный payload.";
        }
        if (payload.amount() > 0 && preCheckoutQuery.getTotalAmount() != payload.amount()) {
            return "Сумма платежа не совпадает.";
        }

        Optional<Draft> draftOpt = database.getDraft(payload.draftId());
        if (draftOpt.isEmpty()) {
            return "Черновик не найден.";
        }

        Draft draft = draftOpt.get();
        if (draft.userId() != preCheckoutQuery.getFrom().getId()) {
            return "Черновик принадлежит другому пользователю.";
        }
        if (draft.status() != DraftStatus.READY) {
            return "Этот черновик уже недоступен для оплаты.";
        }
        TariffPlan tariff = tariffFromDraft(draft);
        if (tariff == null) {
            return "Не выбран тариф.";
        }
        if (payload.amount() > 0 && payload.amount() != tariff.priceStars()) {
            return "Тариф и сумма не совпадают.";
        }
        if (database.getTargetGroupId().isEmpty()) {
            return "Группа для публикации не настроена.";
        }

        return null;
    }

    private void handleSuccessfulPayment(Message message) throws Exception {
        long userId = message.getFrom().getId();
        SuccessfulPayment successfulPayment = message.getSuccessfulPayment();
        InvoicePayload payload = parsePayload(successfulPayment.getInvoicePayload());
        Long draftId = payload == null ? null : payload.draftId();

        if (draftId == null) {
            sendText(message.getChatId(), "Оплата получена, но не удалось определить черновик.", mainMenuKeyboard(database.isAdmin(userId)));
            return;
        }

        Optional<Draft> draftOpt = database.getDraft(draftId);
        if (draftOpt.isEmpty()) {
            sendText(message.getChatId(), "Оплата получена, но черновик не найден. Напишите администратору.", mainMenuKeyboard(database.isAdmin(userId)));
            return;
        }

        Draft draft = draftOpt.get();
        if (draft.userId() != userId) {
            sendText(message.getChatId(), "Оплата получена, но доступ к черновику ограничен.", mainMenuKeyboard(database.isAdmin(userId)));
            return;
        }
        if (draft.status() == DraftStatus.ACTIVE || draft.status() == DraftStatus.COMPLETED || draft.status() == DraftStatus.PUBLISHED) {
            sendText(message.getChatId(), "Этот черновик уже оплачен ранее.", inlineKeyboard(row(button("🚀 Новый пост", CB_MAKE_POST))));
            return;
        }
        if (draft.status() != DraftStatus.READY) {
            sendText(message.getChatId(), "Этот черновик уже недоступен для оплаты.", inlineKeyboard(row(button("🚀 Новый пост", CB_MAKE_POST))));
            return;
        }

        completePaidDraft(
                draft,
                userId,
                message.getChatId(),
                message.getFrom().getUserName(),
                successfulPayment.getTotalAmount(),
                successfulPayment.getCurrency(),
                successfulPayment.getTelegramPaymentChargeId(),
                successfulPayment.getProviderPaymentChargeId(),
                "PAID",
                false
        );
    }

    private void completePaidDraft(
            Draft draft,
            long userId,
            long chatId,
            String payerUsername,
            int amount,
            String currency,
            String telegramPaymentChargeId,
            String providerPaymentChargeId,
            String paidStatus,
            boolean simulated
    ) throws Exception {
        TariffPlan tariff = tariffFromDraft(draft);
        if (tariff == null) {
            sendText(chatId, "Не выбран тариф для автопостинга.", inlineKeyboard(row(button("🚀 Новый пост", CB_MAKE_POST))));
            return;
        }
        database.upsertPayment(
                draft.id(),
                userId,
                payerUsername,
                amount,
                currency,
                telegramPaymentChargeId,
                providerPaymentChargeId,
                paidStatus
        );

        Optional<Long> groupIdOpt = database.getTargetGroupId();
        if (groupIdOpt.isEmpty()) {
            sendText(chatId, """
                    %s
                    Но группа для публикации не задана. Напишите администратору.
                    """.formatted(simulated ? "Тестовая оплата выполнена ✅" : "Оплата прошла успешно ✅"), mainMenuKeyboard(database.isAdmin(userId)));
            return;
        }

        Integer firstMessageId = publishDraftToGroupAndGetMessageId(groupIdOpt.get(), draft);
        if (firstMessageId == null) {
            database.updatePaymentStatusByDraft(draft.id(), simulated ? "AUTOPOST_START_FAILED_TEST" : "AUTOPOST_START_FAILED");
            sendText(chatId, """
                    %s
                    Не удалось сделать первый пост в группе. Проверьте права бота и настройки группы.
                    """.formatted(simulated ? "Тестовая оплата подтверждена." : "Оплата подтверждена."), inlineKeyboard(row(button("🚀 Новый пост", CB_MAKE_POST))));
            return;
        }

        Instant now = Instant.now();
        Instant nextPublishAt = now.plus(Duration.ofHours(tariff.intervalHours()));
        Instant publishUntil = now.plus(CAMPAIGN_DURATION);
        database.activateAutoPosting(draft.id(), nextPublishAt.toString(), publishUntil.toString(), firstMessageId, 1);
        database.updatePaymentStatusByDraft(draft.id(), simulated ? PAY_STATUS_RUNNING_TEST : PAY_STATUS_RUNNING);
        database.clearUserState(userId);
        sendText(chatId, """
                %s
                🚀 Кампания автопостинга на 7 дней запущена.
                Публикация: каждые <b>%d часа(ов)</b>.
                Спасибо за оплату!
                """.formatted(simulated ? "🧪 Тестовая оплата подтверждена." : "✅ Оплата подтверждена.", tariff.intervalHours()), inlineKeyboard(row(button("🚀 Новый пост", CB_MAKE_POST))));
    }

    private boolean publishDraftToGroup(long groupId, Draft draft) {
        return publishDraftToGroupAndGetMessageId(groupId, draft) != null;
    }

    private Integer publishDraftToGroupAndGetMessageId(long groupId, Draft draft) {
        try {
            return sendPreviewMedia(groupId, draft, null).getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to publish draft {}", draft.id(), e);
            return null;
        }
    }

    private void safeAutoPostTick() {
        try {
            processAutoPostTick();
        } catch (Exception e) {
            log.error("Auto-post scheduler failed", e);
        }
    }

    private void processAutoPostTick() throws Exception {
        Optional<Long> groupIdOpt = database.getTargetGroupId();
        if (groupIdOpt.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        List<AutoPostJob> jobs = database.listDueAutoPostJobs(now.toString(), 20);
        for (AutoPostJob job : jobs) {
            Instant publishUntil = parseInstant(job.publishUntil());
            if (publishUntil == null || !now.isBefore(publishUntil)) {
                database.completeAutoPosting(job.draftId(), job.lastGroupMessageId(), job.publishCount());
                database.updatePaymentStatusByDraft(job.draftId(), mapCompletedStatus(job.draftId()));
                continue;
            }

            if (job.lastGroupMessageId() != null) {
                deleteGroupMessageSilently(groupIdOpt.get(), job.lastGroupMessageId());
            }

            Draft draft = new Draft(
                    job.draftId(),
                    job.userId(),
                    job.mediaType(),
                    job.mediaFileId(),
                    job.postText(),
                    DraftStatus.ACTIVE,
                    job.tariffIntervalHours(),
                    null,
                    job.nextPublishAt(),
                    job.publishUntil(),
                    job.publishCount(),
                    job.lastGroupMessageId()
            );

            Integer postedMessageId = publishDraftToGroupAndGetMessageId(groupIdOpt.get(), draft);
            if (postedMessageId == null) {
                database.markAutoPostFailureRetry(job.draftId(), now.plus(FAILED_RETRY_DELAY).toString());
                continue;
            }

            int newCount = job.publishCount() + 1;
            Instant nextPublish = now.plus(Duration.ofHours(job.tariffIntervalHours()));
            if (!nextPublish.isBefore(publishUntil)) {
                database.completeAutoPosting(job.draftId(), postedMessageId, newCount);
                database.updatePaymentStatusByDraft(job.draftId(), mapCompletedStatus(job.draftId()));
            } else {
                database.markAutoPostSuccess(job.draftId(), nextPublish.toString(), newCount, postedMessageId);
            }
        }
    }

    private void adminSetGroupMode(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        database.saveUserState(userId, ConversationState.WAIT_GROUP_ID, null);
        answerCallback(query.getId(), null, false);
        sendText(query.getMessage().getChatId(), """
                📌 Отправьте ID группы для публикации.
                Пример: <code>-1001234567890</code>
                """, inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void handleGroupInput(Message message, long userId) throws Exception {
        if (!database.isAdmin(userId)) {
            database.clearUserState(userId);
            sendMainMenu(message.getChatId(), userId);
            return;
        }
        if (!message.hasText()) {
            sendText(message.getChatId(), "Нужен числовой ID группы.", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }
        Long groupId = parseLongFromText(message.getText());
        if (groupId == null) {
            sendText(message.getChatId(), "Не вижу ID. Отправьте число формата -100...", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        database.setTargetGroupId(groupId);
        database.clearUserState(userId);
        sendText(message.getChatId(), "✅ Группа сохранена: <code>" + groupId + "</code>", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void adminToggleTestMode(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }

        boolean newValue = !isTestModeEnabled();
        database.setTestMode(newValue);
        answerCallback(query.getId(), newValue ? "Тестовый режим включен" : "Тестовый режим выключен", false);
        sendAdminPanel(query.getMessage().getChatId());
    }

    private void adminShowTariffPriceMenu(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        answerCallback(query.getId(), null, false);
        sendText(query.getMessage().getChatId(), """
                💰 <b>Настройка тарифов</b>
                Выберите тариф для изменения цены:
                """, tariffPriceAdminKeyboard());
    }

    private void adminSetTariffPriceMode(CallbackQuery query, long intervalHoursRaw) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }

        int intervalHours = (int) intervalHoursRaw;
        TariffPlan tariff = tariffByInterval(intervalHours);
        if (tariff == null) {
            answerCallback(query.getId(), "Неизвестный тариф", true);
            return;
        }

        database.saveUserState(userId, ConversationState.WAIT_TARIFF_PRICE, Long.valueOf(intervalHours));
        answerCallback(query.getId(), null, false);
        sendText(query.getMessage().getChatId(), """
                Введите новую цену для тарифа «каждые %d часа(ов)».
                Текущая цена: <b>%d Stars</b>
                """.formatted(intervalHours, tariff.priceStars()), inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void handleTariffPriceInput(Message message, UserStateData state, long userId) throws Exception {
        if (!database.isAdmin(userId)) {
            database.clearUserState(userId);
            sendMainMenu(message.getChatId(), userId);
            return;
        }
        if (!message.hasText()) {
            sendText(message.getChatId(), "Нужна цена числом.", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        Long intervalRaw = state.draftId();
        if (intervalRaw == null) {
            database.clearUserState(userId);
            sendAdminPanel(message.getChatId());
            return;
        }

        int intervalHours = intervalRaw.intValue();
        if (tariffByInterval(intervalHours) == null) {
            database.clearUserState(userId);
            sendAdminPanel(message.getChatId());
            return;
        }

        Long price = parseLongFromText(message.getText());
        if (price == null || price <= 0 || price > 50000) {
            sendText(message.getChatId(), "Введите корректную цену от 1 до 50000 Stars.", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        database.setTariffPrice(intervalHours, price.intValue());
        database.clearUserState(userId);
        sendText(message.getChatId(), "✅ Цена тарифа обновлена: каждые " + intervalHours + " часа(ов) — <b>" + price + " Stars</b>", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void adminShowLastPayments(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        answerCallback(query.getId(), null, false);

        List<PaymentInfo> payments = database.listRecentPayments(10);
        if (payments.isEmpty()) {
            sendText(query.getMessage().getChatId(), """
                    💳 Последние оплаты
                    Пока платежей нет.
                    """, inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        StringBuilder sb = new StringBuilder("💳 <b>Последние оплаты</b>\n\n");
        for (PaymentInfo payment : payments) {
            String payer = payment.payerUsername() == null ? "без username" : "@" + payment.payerUsername();
            sb.append("• #").append(payment.id())
                    .append(" | draft ").append(payment.draftId())
                    .append("\n  ").append(payment.amount()).append(" ").append(payment.currency())
                    .append(" | ").append(statusBadge(payment.status()))
                    .append("\n  ").append(payer).append(" (").append(payment.userId()).append(")")
                    .append("\n  ").append(formatTime(payment.createdAt()))
                    .append("\n\n");
        }
        sendText(query.getMessage().getChatId(), sb.toString().trim(), inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void adminShowAdmins(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        answerCallback(query.getId(), null, false);

        List<AdminInfo> admins = database.listAdmins();
        StringBuilder sb = new StringBuilder("👮 <b>Список админов</b>\n\n");
        for (AdminInfo admin : admins) {
            sb.append("• ").append(admin.userId());
            if (admin.username() != null && !admin.username().isBlank()) {
                sb.append(" (@").append(admin.username()).append(")");
            }
            if (admin.userId() == config.ownerId()) {
                sb.append(" — владелец");
            }
            sb.append("\n");
        }
        sendText(query.getMessage().getChatId(), sb.toString().trim(), inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void adminAddMode(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        database.saveUserState(userId, ConversationState.WAIT_NEW_ADMIN_ID, null);
        answerCallback(query.getId(), null, false);
        sendText(query.getMessage().getChatId(), """
                ➕ Отправьте Telegram user ID нового админа.
                Пример: <code>123456789</code>
                """, inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void handleNewAdminInput(Message message, long userId) throws Exception {
        if (!database.isAdmin(userId)) {
            database.clearUserState(userId);
            sendMainMenu(message.getChatId(), userId);
            return;
        }
        if (!message.hasText()) {
            sendText(message.getChatId(), "Нужен числовой user ID.", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        Long newAdminId = parseLongFromText(message.getText());
        if (newAdminId == null || newAdminId <= 0) {
            sendText(message.getChatId(), "Не удалось распознать user ID.", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
            return;
        }

        database.addAdmin(newAdminId, null);
        database.clearUserState(userId);
        sendText(message.getChatId(), "✅ Админ добавлен: <code>" + newAdminId + "</code>", inlineKeyboard(row(button("⬅️ В админ-панель", CB_ADMIN_BACK))));
    }

    private void adminRemoveMenu(CallbackQuery query) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }
        answerCallback(query.getId(), null, false);

        List<AdminInfo> admins = database.listAdmins();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (AdminInfo admin : admins) {
            if (admin.userId() == config.ownerId()) {
                continue;
            }
            String label = "Удалить " + admin.userId();
            if (admin.username() != null && !admin.username().isBlank()) {
                label = "Удалить @" + admin.username();
            }
            rows.add(row(button("➖ " + label, "admin_remove:" + admin.userId())));
        }
        rows.add(row(button("⬅️ В админ-панель", CB_ADMIN_BACK)));

        if (rows.size() == 1) {
            sendText(query.getMessage().getChatId(), "Кроме владельца админов нет.", inlineKeyboard(rows.toArray(List[]::new)));
            return;
        }
        sendText(query.getMessage().getChatId(), "Выберите админа для удаления:", inlineKeyboard(rows.toArray(List[]::new)));
    }

    private void adminRemove(CallbackQuery query, long adminIdToRemove) throws Exception {
        long userId = query.getFrom().getId();
        if (!database.isAdmin(userId)) {
            answerCallback(query.getId(), "Недостаточно прав", true);
            return;
        }

        boolean removed = database.removeAdmin(adminIdToRemove, config.ownerId());
        if (removed) {
            answerCallback(query.getId(), "Админ удалён", false);
        } else {
            answerCallback(query.getId(), "Не удалось удалить (возможно владелец)", true);
        }
        sendAdminPanel(query.getMessage().getChatId());
    }

    private void adminShowPanelText(long chatId) throws Exception {
        Optional<Long> groupId = database.getTargetGroupId();
        String groupValue = groupId.map(String::valueOf).orElse("не задана");
        boolean testMode = isTestModeEnabled();
        TariffPlan t2 = tariffByInterval(2);
        TariffPlan t4 = tariffByInterval(4);
        TariffPlan t6 = tariffByInterval(6);
        sendText(chatId, """
                🛠 <b>Админ-панель</b>

                📌 Группа: <code>%s</code>
                🧪 Тестовый режим: <b>%s</b>
                📅 Автопостинг: <b>7 дней</b>
                💰 Тарифы: 2ч=%d ⭐ | 4ч=%d ⭐ | 6ч=%d ⭐
                Управляйте настройками и модерацией через кнопки ниже.
                """.formatted(groupValue, testMode ? "ВКЛ" : "ВЫКЛ", t2.priceStars(), t4.priceStars(), t6.priceStars()), adminPanelKeyboard(testMode));
    }

    private void sendAdminPanel(long chatId) throws Exception {
        adminShowPanelText(chatId);
    }

    private void cancelDraftFlow(CallbackQuery query, Long draftId, boolean restart) throws Exception {
        long userId = query.getFrom().getId();
        database.clearUserState(userId);
        if (draftId != null) {
            Optional<Draft> draftOpt = database.getDraft(draftId);
            if (draftOpt.isPresent() && draftOpt.get().userId() == userId) {
                database.setDraftStatus(draftId, DraftStatus.CANCELLED);
            }
        }
        answerCallback(query.getId(), restart ? "Начинаем заново" : "Отменено", false);
        if (restart) {
            startPostFlow(query, false);
        } else {
            sendMainMenu(query.getMessage().getChatId(), userId);
        }
    }

    private void sendMainMenu(long chatId, long userId) throws Exception {
        boolean isAdmin = database.isAdmin(userId);
        TariffPlan t2 = tariffByInterval(2);
        TariffPlan t4 = tariffByInterval(4);
        TariffPlan t6 = tariffByInterval(6);
        sendText(chatId, """
                👋 Добро пожаловать!

                Здесь можно запустить автопостинг рекламы на 7 дней.
                Тарифы:
                • каждые 2 часа — %d ⭐
                • каждые 4 часа — %d ⭐
                • каждые 6 часов — %d ⭐
                """.formatted(t2.priceStars(), t4.priceStars(), t6.priceStars()), mainMenuKeyboard(isAdmin));
    }

    private InlineKeyboardMarkup mainMenuKeyboard(boolean isAdmin) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row(button("📝 Сделать пост", CB_MAKE_POST)));
        if (isAdmin) {
            rows.add(row(button("🛠 Админ-панель", CB_ADMIN_MENU)));
        }
        return inlineKeyboard(rows.toArray(List[]::new));
    }

    private InlineKeyboardMarkup adminPanelKeyboard(boolean testMode) {
        String toggleLabel = testMode ? "🧪 Выключить тестовый режим" : "🧪 Включить тестовый режим";
        return inlineKeyboard(
                row(button("📌 Задать группу", CB_ADMIN_SET_GROUP)),
                row(button("💰 Изменить цены тарифов", CB_ADMIN_EDIT_PRICES)),
                row(button(toggleLabel, CB_ADMIN_TOGGLE_TEST_MODE)),
                row(button("💳 Последние оплаты", CB_ADMIN_LAST_PAYMENTS)),
                row(button("👮 Список админов", CB_ADMIN_LIST_ADMINS)),
                row(button("➕ Добавить админа", CB_ADMIN_ADD_ADMIN)),
                row(button("➖ Удалить админа", CB_ADMIN_REMOVE_MENU)),
                row(button("⬅️ Назад", CB_CANCEL_FLOW))
        );
    }

    private InlineKeyboardMarkup tariffPriceAdminKeyboard() {
        TariffPlan t2 = tariffByInterval(2);
        TariffPlan t4 = tariffByInterval(4);
        TariffPlan t6 = tariffByInterval(6);
        return inlineKeyboard(
                row(button("2ч → " + t2.priceStars() + " ⭐", CB_ADMIN_EDIT_PRICE_PREFIX + "2")),
                row(button("4ч → " + t4.priceStars() + " ⭐", CB_ADMIN_EDIT_PRICE_PREFIX + "4")),
                row(button("6ч → " + t6.priceStars() + " ⭐", CB_ADMIN_EDIT_PRICE_PREFIX + "6")),
                row(button("⬅️ В админ-панель", CB_ADMIN_BACK))
        );
    }

    private Message sendPreviewMedia(long chatId, Draft draft, InlineKeyboardMarkup markup) throws TelegramApiException {
        if (draft.mediaType() == MediaType.PHOTO) {
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(String.valueOf(chatId));
            sendPhoto.setPhoto(new InputFile(draft.mediaFileId()));
            sendPhoto.setCaption(draft.postText());
            sendPhoto.setReplyMarkup(markup);
            return execute(sendPhoto);
        }
        if (draft.mediaType() == MediaType.VIDEO) {
            SendVideo sendVideo = new SendVideo();
            sendVideo.setChatId(String.valueOf(chatId));
            sendVideo.setVideo(new InputFile(draft.mediaFileId()));
            sendVideo.setCaption(draft.postText());
            sendVideo.setReplyMarkup(markup);
            return execute(sendVideo);
        }
        throw new TelegramApiException("Unsupported media type");
    }

    private void deleteGroupMessageSilently(long groupId, int messageId) {
        try {
            DeleteMessage delete = new DeleteMessage();
            delete.setChatId(String.valueOf(groupId));
            delete.setMessageId(messageId);
            execute(delete);
        } catch (TelegramApiException e) {
            log.warn("Failed to delete old group message {} in {}", messageId, groupId, e);
        }
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup markup) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");
        if (markup != null) {
            message.setReplyMarkup(markup);
        }
        execute(message);
    }

    private void answerCallback(String callbackId, String text, boolean showAlert) {
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            answer.setShowAlert(showAlert);
            if (text != null && !text.isBlank()) {
                answer.setText(text);
            }
            execute(answer);
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback", e);
        }
    }

    @SafeVarargs
    private InlineKeyboardMarkup inlineKeyboard(List<InlineKeyboardButton>... rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (List<InlineKeyboardButton> row : rows) {
            keyboard.add(row);
        }
        markup.setKeyboard(keyboard);
        return markup;
    }

    private List<InlineKeyboardButton> row(InlineKeyboardButton... buttons) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (InlineKeyboardButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private long parseIdSuffix(String data, String prefix) {
        try {
            return Long.parseLong(data.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private String buildPayload(long draftId, int amount) {
        return "draft:" + draftId + ":" + amount;
    }

    private InvoicePayload parsePayload(String payload) {
        if (payload == null) {
            return null;
        }
        Matcher matcher = PAYLOAD_PATTERN.matcher(payload);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long draftId = Long.parseLong(matcher.group(1));
            String amountRaw = matcher.group(2);
            int amount = amountRaw == null ? -1 : Integer.parseInt(amountRaw);
            return new InvoicePayload(draftId, amount);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLongFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("-?\\d+").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String statusBadge(String status) {
        return switch (status) {
            case "PAID" -> "🟡 Оплачено";
            case PAY_STATUS_RUNNING -> "🟢 Автопостинг активен";
            case PAY_STATUS_COMPLETED -> "✅ Кампания завершена";
            case "PAID_TEST" -> "🧪 Тест: оплачено";
            case PAY_STATUS_RUNNING_TEST -> "🧪 Тест: автопостинг активен";
            case PAY_STATUS_COMPLETED_TEST -> "🧪 Тест: кампания завершена";
            default -> "ℹ️ " + status;
        };
    }

    private boolean isTestModeEnabled() throws SQLException {
        return database.getTestMode(config.testMode());
    }

    private TariffPlan tariffFromDraft(Draft draft) {
        if (draft.tariffIntervalHours() == null || draft.tariffPriceStars() == null) {
            return null;
        }
        TariffPlan tariff = tariffByInterval(draft.tariffIntervalHours());
        if (tariff == null || tariff.priceStars() != draft.tariffPriceStars()) {
            return new TariffPlan(draft.tariffIntervalHours(), draft.tariffPriceStars());
        }
        return tariff;
    }

    private TariffPlan tariffByInterval(int intervalHours) {
        if (intervalHours != 2 && intervalHours != 4 && intervalHours != 6) {
            return null;
        }
        try {
            int fallback = defaultTariffPrice(intervalHours);
            int price = database.getTariffPrice(intervalHours, fallback);
            return new TariffPlan(intervalHours, price);
        } catch (SQLException e) {
            log.warn("Failed to load tariff {}h price, fallback will be used", intervalHours, e);
            return new TariffPlan(intervalHours, defaultTariffPrice(intervalHours));
        }
    }

    private int defaultTariffPrice(int intervalHours) {
        return switch (intervalHours) {
            case 2 -> 1000;
            case 4 -> 700;
            case 6 -> 500;
            default -> throw new IllegalArgumentException("Unsupported tariff interval: " + intervalHours);
        };
    }

    private String formatTariffLinesForText() {
        TariffPlan t2 = tariffByInterval(2);
        TariffPlan t4 = tariffByInterval(4);
        TariffPlan t6 = tariffByInterval(6);
        return """
                • Каждые 2 часа — <b>%d Stars</b>
                • Каждые 4 часа — <b>%d Stars</b>
                • Каждые 6 часов — <b>%d Stars</b>
                """.formatted(t2.priceStars(), t4.priceStars(), t6.priceStars()).trim();
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String mapCompletedStatus(long draftId) throws SQLException {
        Optional<String> current = database.getPaymentStatusByDraft(draftId);
        if (current.isPresent() && PAY_STATUS_RUNNING_TEST.equals(current.get())) {
            return PAY_STATUS_COMPLETED_TEST;
        }
        return PAY_STATUS_COMPLETED;
    }

    private record InvoicePayload(long draftId, int amount) {
    }

    private record TariffPlan(int intervalHours, int priceStars) {
    }

    private String formatTime(String isoInstant) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(isoInstant).withZoneSameInstant(ZoneOffset.UTC);
            return zdt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'", Locale.forLanguageTag("ru")));
        } catch (Exception ignored) {
            return isoInstant;
        }
    }

    private boolean isPrivateMessage(Message message) {
        return message.getChat() != null && Boolean.TRUE.equals(message.getChat().isUserChat());
    }

    private boolean isPrivateCallback(CallbackQuery query) {
        if (query.getMessage() == null) {
            return false;
        }
        return query.getMessage().isUserMessage();
    }
}
