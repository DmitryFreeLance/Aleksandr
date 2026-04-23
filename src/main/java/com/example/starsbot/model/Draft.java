package com.example.starsbot.model;

public record Draft(
        long id,
        long userId,
        MediaType mediaType,
        String mediaFileId,
        String postText,
        DraftStatus status
) {
}

