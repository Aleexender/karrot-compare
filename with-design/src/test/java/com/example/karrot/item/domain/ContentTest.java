package com.example.karrot.item.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentTest {

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("빈 문자열 내용은 허용한다")
    void acceptsEmpty() {
        assertThat(new Content("").value()).isEmpty();
    }

    @Test
    @DisplayName("이모지 5000자(UTF-16 10000자) 내용은 허용한다")
    void acceptsFiveThousandEmoji() {
        String value = EMOJI.repeat(5000);

        assertThat(new Content(value).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("이모지 5001자 내용은 거부한다")
    void rejectsFiveThousandAndOneEmoji() {
        assertThatThrownBy(() -> new Content(EMOJI.repeat(5001))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("내용이 null이면 만들 수 없다 (내용 없음은 Item.content null로 표현)")
    void rejectsNull() {
        assertThatThrownBy(() -> new Content(null)).isInstanceOf(NullPointerException.class);
    }
}
