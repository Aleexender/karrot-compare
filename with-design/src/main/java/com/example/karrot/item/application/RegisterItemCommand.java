package com.example.karrot.item.application;

public record RegisterItemCommand(
        Long userId,
        String idempotencyKey,
        String title,
        String content,
        long price,
        boolean negotiable,
        Long categoryId) {
}
