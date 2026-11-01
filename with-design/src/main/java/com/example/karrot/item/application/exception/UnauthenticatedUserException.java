package com.example.karrot.item.application.exception;

public class UnauthenticatedUserException extends RuntimeException {

    public UnauthenticatedUserException(String detail) {
        super(detail);
    }
}
