package com.example.karrot.item.application;

import com.example.karrot.item.domain.Item;

public record ItemRegistration(Item item, boolean created) {

    static ItemRegistration newItem(Item item) {
        return new ItemRegistration(item, true);
    }

    static ItemRegistration existingItem(Item item) {
        return new ItemRegistration(item, false);
    }
}
