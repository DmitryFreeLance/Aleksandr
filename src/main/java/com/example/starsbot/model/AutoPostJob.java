package com.example.starsbot.model;

public record AutoPostJob(
        long draftId,
        long userId,
        MediaType mediaType,
        String mediaFileId,
        String postText,
        int tariffIntervalHours,
        String nextPublishAt,
        String publishUntil,
        int publishCount,
        Integer lastGroupMessageId
) {
}
