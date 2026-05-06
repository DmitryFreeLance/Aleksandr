package com.example.starsbot.model;

public record DraftMedia(
        long draftId,
        int position,
        MediaType mediaType,
        String fileId
) {
}

