package com.example.starsbot.model;

public record PaymentInfo(
        long id,
        long draftId,
        long userId,
        String payerUsername,
        int amount,
        String currency,
        String status,
        String createdAt
) {
}

