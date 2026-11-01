package com.example.karrot.item.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record Price(@Column(name = "price", nullable = false) long value) {

    public Price {
        if (value < 0) {
            throw new IllegalArgumentException("price must not be negative: " + value);
        }
    }
}
