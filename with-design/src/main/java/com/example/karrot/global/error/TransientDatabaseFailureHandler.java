package com.example.karrot.global.error;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TransientDatabaseFailureHandler {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    // Spring wraps these failures in different exception types depending on where they surface
    // (transaction begin, statement, commit), so the JDBC cause decides instead of the wrapper type.
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ProblemDetail handle(RuntimeException exception) {
        if (!isTransient(exception)) {
            throw exception;
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The database is temporarily busy. Retry the request later.");
    }

    private boolean isTransient(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            // HikariCP throws this when no pooled connection becomes available within connection-timeout.
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                    || sqlException.getErrorCode() == MYSQL_DEADLOCK)) {
                return true;
            }
        }
        return false;
    }
}
