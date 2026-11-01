package com.example.karrot.item.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TitleTest {

    private static final String EMOJI = "😀";
    // "e" followed by U+0301 (combining acute accent): renders as one letter but is two code points.
    private static final String COMBINING_PAIR = "e\u0301";

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u3000"})
    @DisplayName("제목이 비어 있거나 공백뿐이면 만들 수 없다")
    void rejectsBlank(String value) {
        assertThatThrownBy(() -> new Title(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("제목이 null이면 만들 수 없다")
    void rejectsNull() {
        assertThatThrownBy(() -> new Title(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("이모지 100자(UTF-16 200자) 제목은 허용한다")
    void acceptsHundredEmoji() {
        String value = EMOJI.repeat(100);

        assertThat(new Title(value).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("이모지 101자 제목은 거부한다")
    void rejectsHundredAndOneEmoji() {
        assertThatThrownBy(() -> new Title(EMOJI.repeat(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한글 100자 제목은 허용한다")
    void acceptsHundredHangul() {
        String value = "가".repeat(100);

        assertThat(new Title(value).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("한글 101자 제목은 거부한다")
    void rejectsHundredAndOneHangul() {
        assertThatThrownBy(() -> new Title("가".repeat(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("e+U+0301 50쌍(코드 포인트 100개) 제목은 정규화하지 않은 그대로 허용한다")
    void acceptsFiftyCombiningPairs() {
        String value = COMBINING_PAIR.repeat(50);

        assertThat(new Title(value).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("e+U+0301 51쌍(코드 포인트 102개)은 NFC로 51자가 되더라도 거부한다")
    void rejectsFiftyOneCombiningPairs() {
        assertThatThrownBy(() -> new Title(COMBINING_PAIR.repeat(51))).isInstanceOf(IllegalArgumentException.class);
    }
}
