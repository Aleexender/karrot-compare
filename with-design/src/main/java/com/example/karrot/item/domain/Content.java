package com.example.karrot.item.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

// Missing content is a null Item.content, never Content(null): Hibernate reads an all-NULL embedded as null,
// so allowing Content(null) would make the saved object and the reloaded one differ.
// Length is counted in code points without normalization, matching MySQL CHAR_LENGTH.
@Embeddable
public record Content(@Column(name = "content") String value) {

    public static final int MAX_LENGTH = 5000;

    public Content {
        Objects.requireNonNull(value, "content must not be null");
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("content must be at most " + MAX_LENGTH + " characters");
        }
    }
}
