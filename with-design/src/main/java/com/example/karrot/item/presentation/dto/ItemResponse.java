package com.example.karrot.item.presentation.dto;

import com.example.karrot.item.domain.Item;
import com.example.karrot.item.domain.ItemStatus;
import java.time.LocalDateTime;

public record ItemResponse(
        Long id,
        Long userId,
        Long categoryId,
        String title,
        String content,
        long price,
        boolean negotiable,
        ItemStatus status,
        LocalDateTime createdAt) {

    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getUserId(),
                item.getCategoryId(),
                item.getTitle().value(),
                item.getContent() == null ? null : item.getContent().value(),
                item.getPrice().value(),
                item.isNegotiable(),
                item.getStatus(),
                item.getCreatedAt());
    }
}
