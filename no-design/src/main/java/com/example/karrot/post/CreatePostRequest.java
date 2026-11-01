package com.example.karrot.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotNull Long sellerId,
        @NotBlank @Size(max = 100) String title,
        @NotBlank String content,
        @NotNull @PositiveOrZero Integer price,
        @NotBlank @Size(max = 50) String location
) {
}
