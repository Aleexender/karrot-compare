package com.example.karrot.item.presentation;

import com.example.karrot.item.application.exception.ItemNotFoundException;
import com.example.karrot.item.application.exception.UnauthenticatedUserException;
import com.example.karrot.item.application.exception.UnknownCategoryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ItemExceptionHandler {

    @ExceptionHandler(UnauthenticatedUserException.class)
    public ProblemDetail handleUnauthenticatedUser(UnauthenticatedUserException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(UnknownCategoryException.class)
    public ProblemDetail handleUnknownCategory(UnknownCategoryException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ItemNotFoundException.class)
    public ProblemDetail handleItemNotFound(ItemNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
