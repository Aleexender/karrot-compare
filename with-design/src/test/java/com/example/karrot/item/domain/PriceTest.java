package com.example.karrot.item.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceTest {

    @Test
    @DisplayName("가격이 음수(-1)면 만들 수 없다")
    void rejectsNegative() {
        assertThatThrownBy(() -> new Price(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("가격 0(나눔)은 허용한다")
    void acceptsZero() {
        assertThat(new Price(0).value()).isZero();
    }
}
