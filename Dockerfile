FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/telegram-stars-post-bot-1.0.0-jar-with-dependencies.jar /app/app.jar
VOLUME ["/data"]

ENV BOT_DB_PATH=/data/bot.db

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
