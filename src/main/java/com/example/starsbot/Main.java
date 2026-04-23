package com.example.starsbot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.fromEnv();
        Database database = new Database(config.dbPath());
        database.init();
        database.ensureOwnerAdmin(config.ownerId());
        database.ensureRuntimeDefaults(config.testMode(), config.postPriceStars());

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new StarsPostBot(config, database));
    }
}
