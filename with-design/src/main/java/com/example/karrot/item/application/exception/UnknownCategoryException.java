package com.example.karrot.item.application.exception;

public class UnknownCategoryException extends RuntimeException {

    public UnknownCategoryException(Long categoryId) {
        super("Unknown categoryId: " + categoryId);
    }
}
