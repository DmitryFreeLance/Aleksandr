package com.example.starsbot.model;

public record Draft(
        long id,
        long userId,
        MediaType mediaType,
        String mediaFileId,
        String postText,
        DraftStatus status,
        Integer tariffIntervalHours,
        Integer tariffPriceStars,
        String nextPublishAt,
        String publishUntil,
        int publishCount,
        Integer lastGroupMessageId,
        Integer lastGroupMediaCount
) {
}
