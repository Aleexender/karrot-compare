package com.example.karrot.item;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.karrot.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ItemsTableConstraintTest extends IntegrationTestSupport {

    private long userId;

    @BeforeEach
    void setUp() {
        userId = createUser();
    }

    @Test
    @DisplayName("애플리케이션을 거치지 않아도 음수 가격은 DB CHECK 제약으로 거부된다")
    void negativePriceViolatesCheckConstraint() {
        assertThatThrownBy(() -> insertItem(-1, "ON_SALE"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_items_price");
    }

    @Test
    @DisplayName("애플리케이션을 거치지 않아도 정의되지 않은 판매 상태는 DB CHECK 제약으로 거부된다")
    void unknownStatusViolatesCheckConstraint() {
        assertThatThrownBy(() -> insertItem(1000, "DELETED"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_items_status");
    }

    private void insertItem(long price, String status) {
        jdbcTemplate.update("""
                INSERT INTO items (user_id, category_id, title, content, price, negotiable, status, idempotency_key, created_at)
                VALUES (?, 1, '제목', NULL, ?, FALSE, ?, 'raw-insert-key', ?)""",
                userId, price, status, LocalDateTime.now());
    }
}
