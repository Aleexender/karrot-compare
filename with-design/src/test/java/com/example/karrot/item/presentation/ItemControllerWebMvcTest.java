package com.example.karrot.item.presentation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.karrot.global.config.JacksonCoercionConfig;
import com.example.karrot.item.application.ItemReader;
import com.example.karrot.item.application.ItemRegistration;
import com.example.karrot.item.application.ItemService;
import com.example.karrot.item.domain.Content;
import com.example.karrot.item.domain.Item;
import com.example.karrot.item.domain.Price;
import com.example.karrot.item.domain.Title;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(ItemController.class)
@Import(JacksonCoercionConfig.class)
class ItemControllerWebMvcTest {

    private static final String USER_ID = "1";
    private static final String KEY = "key-1";
    private static final String EMOJI = "😀";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ItemService itemService;

    @MockitoBean
    private ItemReader itemReader;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTitles")
    @DisplayName("제목이 없거나 공백이거나 100자를 넘으면 400이고 등록을 시도하지 않는다")
    void invalidTitleIsBadRequest(String description, String body) throws Exception {
        postItem(USER_ID, KEY, body).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> invalidTitles() {
        return Stream.of(
                Arguments.of("제목 누락", bodyWithout("title")),
                Arguments.of("제목 null", bodyWith("title", "null")),
                Arguments.of("제목 빈 문자열", bodyWith("title", "\"\"")),
                Arguments.of("제목 공백만", bodyWith("title", "\"   \"")),
                Arguments.of("제목 101자(한글)", bodyWith("title", quoted("가".repeat(101)))),
                Arguments.of("제목 101자(이모지)", bodyWith("title", quoted(EMOJI.repeat(101)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tooLongContents")
    @DisplayName("내용이 5000자를 넘으면 400이고 등록을 시도하지 않는다")
    void tooLongContentIsBadRequest(String description, String content) throws Exception {
        postItem(USER_ID, KEY, bodyWith("content", quoted(content))).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> tooLongContents() {
        return Stream.of(
                Arguments.of("내용 5001자(한글)", "나".repeat(5001)),
                Arguments.of("내용 5001자(이모지)", EMOJI.repeat(5001)));
    }

    @Test
    @DisplayName("이모지 제목 100자(UTF-16 200자)는 그대로 등록을 요청하고 201")
    void hundredEmojiTitleIsCreated() throws Exception {
        String title = EMOJI.repeat(100);
        givenRegistered(title, "거의 새것");

        postItem(USER_ID, KEY, bodyWith("title", quoted(title))).andExpect(status().isCreated());

        verify(itemService).register(argThat(command -> command.title().equals(title)));
    }

    @Test
    @DisplayName("이모지 내용 5000자(UTF-16 10000자)는 그대로 등록을 요청하고 201")
    void fiveThousandEmojiContentIsCreated() throws Exception {
        String content = EMOJI.repeat(5000);
        givenRegistered("맥북 에어 M3", content);

        postItem(USER_ID, KEY, bodyWith("content", quoted(content))).andExpect(status().isCreated());

        verify(itemService).register(argThat(command -> command.content().equals(content)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPrices")
    @DisplayName("가격이 없거나 음수면 400이고 등록을 시도하지 않는다")
    void invalidPriceIsBadRequest(String description, String body) throws Exception {
        postItem(USER_ID, KEY, body).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> invalidPrices() {
        return Stream.of(
                Arguments.of("가격 누락", bodyWithout("price")),
                Arguments.of("가격 null", bodyWith("price", "null")),
                Arguments.of("가격 -1", bodyWith("price", "-1")),
                Arguments.of("가격 Long 최솟값", bodyWith("price", Long.toString(Long.MIN_VALUE))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingCategories")
    @DisplayName("카테고리가 없으면 400이고 등록을 시도하지 않는다")
    void missingCategoryIsBadRequest(String description, String body) throws Exception {
        postItem(USER_ID, KEY, body).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> missingCategories() {
        return Stream.of(
                Arguments.of("카테고리 누락", bodyWithout("categoryId")),
                Arguments.of("카테고리 null", bodyWith("categoryId", "null")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldTypeMismatches")
    @DisplayName("필드 타입이 맞지 않으면 변환하지 않고 400")
    void fieldTypeMismatchIsBadRequest(String description, String body) throws Exception {
        postItem(USER_ID, KEY, body).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> fieldTypeMismatches() {
        return Stream.of(
                Arguments.of("가격 소수 12.5", bodyWith("price", "12.5")),
                Arguments.of("가격 문자열 \"1000\"", bodyWith("price", "\"1000\"")),
                Arguments.of("가격 Long 범위 초과", bodyWith("price", "9223372036854775808")),
                Arguments.of("제목 숫자 123", bodyWith("title", "123")),
                Arguments.of("제목 불리언 true", bodyWith("title", "true")),
                Arguments.of("네고 문자열 \"true\"", bodyWith("negotiable", "\"true\"")),
                Arguments.of("카테고리 문자열 \"1\"", bodyWith("categoryId", "\"1\"")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedJson")
    @DisplayName("JSON 형식이 잘못되면 400")
    void malformedJsonIsBadRequest(String description, String body) throws Exception {
        postItem(USER_ID, KEY, body).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> malformedJson() {
        return Stream.of(
                Arguments.of("빈 본문", ""),
                Arguments.of("닫히지 않은 객체", "{\"title\": \"맥북\""),
                Arguments.of("배열", "[]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIdempotencyKeys")
    @DisplayName("Idempotency-Key가 없거나 공백이거나 64자를 넘거나 ASCII가 아니면 400")
    void invalidIdempotencyKeyIsBadRequest(String description, String key) throws Exception {
        postItem(USER_ID, key, validBody()).andExpect(status().isBadRequest());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> invalidIdempotencyKeys() {
        return Stream.of(
                Arguments.of("헤더 없음", null),
                Arguments.of("빈 문자열", ""),
                Arguments.of("공백만", "   "),
                Arguments.of("65자", "k".repeat(65)),
                Arguments.of("비ASCII", "키-123"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUserIds")
    @DisplayName("X-User-Id가 없거나 숫자 형식이 아니면 401이고 등록을 시도하지 않는다")
    void invalidUserIdIsUnauthorized(String description, String userId) throws Exception {
        postItem(userId, KEY, validBody()).andExpect(status().isUnauthorized());

        verifyNoInteractions(itemService);
    }

    static Stream<Arguments> invalidUserIds() {
        return Stream.of(
                Arguments.of("헤더 없음", null),
                Arguments.of("빈 문자열", ""),
                Arguments.of("문자", "abc"),
                Arguments.of("Long 범위 초과", "9223372036854775808"));
    }

    @Test
    @DisplayName("게시글 id가 숫자가 아니면 400")
    void nonNumericItemIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/posts/abc")).andExpect(status().isBadRequest());

        verifyNoInteractions(itemReader);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transientDatabaseFailures")
    @DisplayName("커넥션 대기 초과·잠금 대기 초과·deadlock이면 503")
    void transientDatabaseFailureIsServiceUnavailable(String description, RuntimeException failure)
            throws Exception {
        given(itemService.register(any())).willThrow(failure);

        postItem(USER_ID, KEY, validBody()).andExpect(status().isServiceUnavailable());
    }

    static Stream<Arguments> transientDatabaseFailures() {
        return Stream.of(
                Arguments.of("커넥션 풀 대기 초과", new CannotCreateTransactionException("Could not open JPA EntityManager",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available"))),
                Arguments.of("잠금 대기 초과(1205)", new CannotAcquireLockException("could not execute statement",
                        new SQLException("Lock wait timeout exceeded", "HY000", 1205))),
                Arguments.of("deadlock(1213)", new CannotAcquireLockException("could not execute statement",
                        new SQLException("Deadlock found when trying to get lock", "40001", 1213))),
                Arguments.of("커밋 중 deadlock(1213)", new TransactionSystemException("Could not commit",
                        new SQLException("Deadlock found when trying to get lock", "40001", 1213))));
    }

    @Test
    @DisplayName("일시적이지 않은 DB 오류는 503으로 바꾸지 않는다")
    void nonTransientDatabaseFailureIsNotServiceUnavailable() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("could not execute statement",
                new SQLException("Cannot add or update a child row", "23000", 1452));
        given(itemService.register(any())).willThrow(failure);

        assertThatThrownBy(() -> postItem(USER_ID, KEY, validBody())).hasCause(failure);
    }

    private void givenRegistered(String title, String content) {
        Item item = new Item(1L, 1L, new Title(title), new Content(content), new Price(1_200_000L), true, KEY);
        given(itemService.register(any())).willReturn(new ItemRegistration(item, true));
    }

    private ResultActions postItem(String userId, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/posts").contentType(MediaType.APPLICATION_JSON).content(body);
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static Map<String, String> validFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "\"맥북 에어 M3\"");
        fields.put("content", "\"거의 새것\"");
        fields.put("price", "1200000");
        fields.put("negotiable", "true");
        fields.put("categoryId", "1");
        return fields;
    }

    private static String validBody() {
        return toJson(validFields());
    }

    private static String bodyWith(String field, String rawJsonValue) {
        Map<String, String> fields = validFields();
        fields.put(field, rawJsonValue);
        return toJson(fields);
    }

    private static String bodyWithout(String field) {
        Map<String, String> fields = validFields();
        fields.remove(field);
        return toJson(fields);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static String toJson(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> quoted(field.getKey()) + ":" + field.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
