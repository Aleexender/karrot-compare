package com.example.karrot.item.application.exception;

public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(Long itemId) {
        super("Item not found: " + itemId);
    }
}
