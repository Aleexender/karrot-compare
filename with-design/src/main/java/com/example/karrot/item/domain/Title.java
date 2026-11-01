package com.example.karrot.item.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

// Length is counted in code points without normalization: that is exactly how MySQL CHAR_LENGTH counts the stored text.
@Embeddable
public record Title(@Column(name = "title", nullable = false, length = Title.MAX_LENGTH) String value) {

    public static final int MAX_LENGTH = 100;

    public Title {
        Objects.requireNonNull(value, "title must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("title must be at most " + MAX_LENGTH + " characters");
        }
    }
}
