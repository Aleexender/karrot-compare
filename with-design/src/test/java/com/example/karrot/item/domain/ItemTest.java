package com.example.karrot.item.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemTest {

    @Test
    @DisplayName("새 게시글은 항상 판매중(ON_SALE) 상태로 시작한다")
    void startsOnSale() {
        Item item = new Item(1L, 1L, new Title("제목"), null, new Price(0), false, "key");

        assertThat(item.getStatus()).isEqualTo(ItemStatus.ON_SALE);
    }
}
