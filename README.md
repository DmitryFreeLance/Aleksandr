# Telegram Stars Post Bot (Java)

Бот для продажи публикаций в группе за Telegram Stars.

## Что умеет

- Полный flow для пользователя:
  - `Сделать пост` → загрузка фото/видео → текст → `Подтвердить` → предпросмотр → `Оплатить`.
  - После успешной оплаты пост мгновенно публикуется в группе.
- Все кнопки в боте — inline.
- Админ-панель:
  - задать ID группы;
  - задать стоимость поста в Stars;
  - включать/выключать тестовый режим;
  - посмотреть последние оплаты;
  - посмотреть список админов;
  - добавить/удалить админа.
- Хранение данных: SQLite.
- Контейнеризация: Dockerfile.

## Переменные окружения

- `BOT_TOKEN` — токен бота.
- `BOT_USERNAME` — username бота (без `@`).
- `BOT_OWNER_ID` — Telegram user ID владельца (становится админом автоматически).
- `BOT_DB_PATH` — путь к SQLite, по умолчанию `./bot.db`.
- `POST_PRICE_STARS` — цена публикации в Stars, по умолчанию `100`.
- `TEST_MODE` — `true/false`, стартовое значение тестового режима (по умолчанию `true`, потом переключается из админ-панели).

## Запуск локально

```bash
mvn clean package
BOT_TOKEN=... \
BOT_USERNAME=... \
BOT_OWNER_ID=123456789 \
TEST_MODE=true \
java -jar target/telegram-stars-post-bot-1.0.0-jar-with-dependencies.jar
```

## Docker

```bash
docker build -t stars-post-bot .
docker run -d \
  --name stars-post-bot \
  -e BOT_TOKEN=... \
  -e BOT_USERNAME=... \
  -e BOT_OWNER_ID=123456789 \
  -e TEST_MODE=true \
  -e BOT_DB_PATH=/data/bot.db \
  -v $(pwd)/data:/data \
  stars-post-bot
```
