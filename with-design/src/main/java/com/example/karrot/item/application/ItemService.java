package com.example.karrot.item.application;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    private static final String IDEMPOTENCY_CONSTRAINT = "uk_items_user_idempotency";

    private final ItemCreator itemCreator;
    private final ItemReader itemReader;

    public ItemService(ItemCreator itemCreator, ItemReader itemReader) {
        this.itemCreator = itemCreator;
        this.itemReader = itemReader;
    }

    // Deliberately not @Transactional: the conflict must be caught after the creating transaction has rolled back.
    // Re-reading inside it would fail on commit (rollback-only) and its REPEATABLE READ snapshot cannot see the
    // concurrent winner's row, so the existing item is read in a new read-only transaction instead.
    public ItemRegistration register(RegisterItemCommand command) {
        try {
            return new ItemRegistration(itemCreator.create(command), true);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyKeyConflict(exception)) {
                throw exception;
            }
            return new ItemRegistration(
                    itemReader.getByIdempotencyKey(command.userId(), command.idempotencyKey()), false);
        }
    }

    private boolean isIdempotencyKeyConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY
                    && sqlException.getMessage().contains(IDEMPOTENCY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
