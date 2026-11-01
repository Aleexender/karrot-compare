package com.example.karrot.item.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.karrot.item.domain.Item;
import com.example.karrot.item.domain.Price;
import com.example.karrot.item.domain.Title;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

class ItemServiceTest {

    private static final long USER_ID = 7L;
    private static final String KEY = "key-1";

    private final ItemCreator itemCreator = mock(ItemCreator.class);
    private final ItemReader itemReader = mock(ItemReader.class);
    private final ItemService itemService = new ItemService(itemCreator, itemReader);
    private final RegisterItemCommand command = new RegisterItemCommand(USER_ID, KEY, "제목", null, 1000L, false, 1L);

    @Test
    @DisplayName("멱등 키 UNIQUE 제약 충돌이면 (유저, 키)로 기존 게시글을 다시 읽어 '새로 만들지 않음'으로 돌려준다")
    void idempotencyConflictReturnsExistingItem() {
        given(itemCreator.create(command)).willThrow(integrityViolation(1062,
                "Duplicate entry '7-key-1' for key 'items.uk_items_user_idempotency'"));
        Item existing = new Item(USER_ID, 1L, new Title("최초 제목"), null, new Price(500), false, KEY);
        given(itemReader.getByIdempotencyKey(USER_ID, KEY)).willReturn(existing);

        ItemRegistration registration = itemService.register(command);

        assertThat(registration.created()).isFalse();
        assertThat(registration.item()).isSameAs(existing);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherIntegrityViolations")
    @DisplayName("멱등 키 제약이 아닌 무결성 오류는 중복으로 취급하지 않고 그대로 던진다")
    void otherIntegrityViolationIsRethrown(String description, DataIntegrityViolationException violation) {
        given(itemCreator.create(command)).willThrow(violation);

        assertThatThrownBy(() -> itemService.register(command)).isSameAs(violation);
        verifyNoInteractions(itemReader);
    }

    static Stream<Arguments> otherIntegrityViolations() {
        return Stream.of(
                Arguments.of("다른 UNIQUE 키 중복(1062)",
                        integrityViolation(1062, "Duplicate entry '1' for key 'items.PRIMARY'")),
                Arguments.of("외래 키 위반(1452)", integrityViolation(1452,
                        "Cannot add or update a child row: a foreign key constraint fails (CONSTRAINT `fk_items_user`)")),
                Arguments.of("길이 초과(1406)", integrityViolation(1406, "Data too long for column 'title' at row 1")));
    }

    private static DataIntegrityViolationException integrityViolation(int errorCode, String message) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(message, "23000", errorCode));
    }
}
