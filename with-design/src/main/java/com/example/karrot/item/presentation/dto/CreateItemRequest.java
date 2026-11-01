package com.example.karrot.item.presentation.dto;

import com.example.karrot.item.application.RegisterItemCommand;
import com.example.karrot.item.domain.Content;
import com.example.karrot.item.domain.Title;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.CodePointLength;

// Lengths are code points without normalization so the check matches MySQL CHAR_LENGTH on the stored text.
public record CreateItemRequest(
        @NotBlank @CodePointLength(max = Title.MAX_LENGTH) String title,
        @CodePointLength(max = Content.MAX_LENGTH) String content,
        // Boxed so a missing price is rejected instead of silently becoming 0 (free).
        @NotNull @PositiveOrZero Long price,
        Boolean negotiable,
        @NotNull Long categoryId) {

    public CreateItemRequest {
        if (negotiable == null) {
            negotiable = false;
        }
    }

    public RegisterItemCommand toCommand(Long userId, String idempotencyKey) {
        return new RegisterItemCommand(userId, idempotencyKey, title, content, price, negotiable, categoryId);
    }
}
