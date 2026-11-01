package com.example.karrot.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.karrot.item.domain.ItemStatus;
import com.example.karrot.item.presentation.dto.ItemResponse;
import com.example.karrot.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostApiIntegrationTest extends IntegrationTestSupport {

    private long userId;

    @BeforeEach
    void setUp() {
        userId = createUser();
    }

    @Test
    @DisplayName("등록하면 201과 Location: /posts/{id}, 판매중 상태의 게시글 본문을 돌려준다")
    void createReturnsCreatedItem() throws Exception {
        HttpResponse<String> response = postItem(userId, newKey(), validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        ItemResponse item = read(response);
        assertThat(response.headers().firstValue("Location")).hasValue("/posts/" + item.id());
        assertThat(item.userId()).isEqualTo(userId);
        assertThat(item.categoryId()).isEqualTo(1L);
        assertThat(item.title()).isEqualTo("맥북 에어 M3");
        assertThat(item.content()).isEqualTo("거의 새것");
        assertThat(item.price()).isEqualTo(1_200_000L);
        assertThat(item.negotiable()).isTrue();
        assertThat(item.status()).isEqualTo(ItemStatus.ON_SALE);
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("등록한 게시글은 DB에 ON_SALE 상태로 저장된다")
    void createStoresOnSaleStatus() throws Exception {
        long id = read(postItem(userId, newKey(), validBody())).id();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM items WHERE id = ?", String.class, id))
                .isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("Location이 가리키는 게시글을 헤더 없이 조회하면 200과 등록 응답과 같은 본문")
    void getByLocationWithoutHeaders() throws Exception {
        HttpResponse<String> created = postItem(userId, newKey(), validBody());

        HttpResponse<String> fetched = get(created.headers().firstValue("Location").orElseThrow());

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(read(fetched)).isEqualTo(read(created));
    }

    @Test
    @DisplayName("존재하지 않는 게시글을 조회하면 404 ProblemDetail")
    void getUnknownItemReturnsNotFound() throws Exception {
        HttpResponse<String> response = get("/posts/" + Long.MAX_VALUE);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
    }

    @Test
    @DisplayName("같은 유저가 같은 키로 다른 본문을 다시 보내면 200과 최초 게시글을 돌려주고 새로 만들지 않는다")
    void retryWithSameKeyReturnsOriginal() throws Exception {
        String key = newKey();
        HttpResponse<String> first = postItem(userId, key, validBody());
        Map<String, Object> changed = validBody();
        changed.put("title", "다른 제목");
        changed.put("price", 5L);

        HttpResponse<String> retry = postItem(userId, key, changed);

        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(read(retry)).isEqualTo(read(first));
        assertThat(countItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 유저가 같은 키를 쓰면 각각 새 게시글로 201")
    void sameKeyFromDifferentUsersCreatesBoth() throws Exception {
        String key = newKey();
        long otherUserId = createUser();

        int first = postItem(userId, key, validBody()).statusCode();
        int second = postItem(otherUserId, key, validBody()).statusCode();

        assertThat(first).isEqualTo(201);
        assertThat(second).isEqualTo(201);
        assertThat(countItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("대소문자만 다른 키는 별개의 게시글로 201")
    void keysDifferingOnlyInCaseAreDistinct() throws Exception {
        HttpResponse<String> lower = postItem(userId, "post-key-abc", validBody());
        HttpResponse<String> upper = postItem(userId, "post-key-ABC", validBody());

        assertThat(lower.statusCode()).isEqualTo(201);
        assertThat(upper.statusCode()).isEqualTo(201);
        assertThat(read(upper).id()).isNotEqualTo(read(lower).id());
        assertThat(countItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("존재하지 않는 유저로 등록하면 401이고 저장하지 않는다")
    void unknownUserReturnsUnauthorized() throws Exception {
        HttpResponse<String> response = postItem(Long.MAX_VALUE, newKey(), validBody());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(countItems()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 카테고리로 등록하면 400이고 저장하지 않는다")
    void unknownCategoryReturnsBadRequest() throws Exception {
        Map<String, Object> body = validBody();
        body.put("categoryId", 999L);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(countItems()).isZero();
    }

    @Test
    @DisplayName("내용을 생략하면 201이고 내용은 null로 저장된다")
    void contentOmittedIsCreated() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("content");

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "content")).isNull();
    }

    @Test
    @DisplayName("내용을 생략해 등록한 게시글을 조회하면 200이고 content는 null")
    void contentOmittedIsReadBackAsNull() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("content");
        HttpResponse<String> created = postItem(userId, newKey(), body);

        HttpResponse<String> fetched = get(created.headers().firstValue("Location").orElseThrow());

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(read(fetched).content()).isNull();
    }

    @Test
    @DisplayName("내용이 빈 문자열이면 201이고 빈 문자열로 저장된다")
    void emptyContentIsCreated() throws Exception {
        Map<String, Object> body = validBody();
        body.put("content", "");

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "content")).isEqualTo("");
    }

    @Test
    @DisplayName("네고 가능 여부를 생략하면 false로 저장된다")
    void negotiableOmittedDefaultsToFalse() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("negotiable");

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(read(response).negotiable()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT negotiable FROM items WHERE id = ?", Boolean.class,
                read(response).id())).isFalse();
    }

    @Test
    @DisplayName("제목이 정확히 100자(한글)면 201이고 그대로 저장된다")
    void titleOfMaxLengthIsCreated() throws Exception {
        String title = "가".repeat(100);
        Map<String, Object> body = validBody();
        body.put("title", title);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "title")).isEqualTo(title);
    }

    @Test
    @DisplayName("제목이 이모지 100자(UTF-16 200자)면 201이고 DB CHAR_LENGTH도 100")
    void emojiTitleOfMaxLengthIsCreated() throws Exception {
        String title = "😀".repeat(100);
        Map<String, Object> body = validBody();
        body.put("title", title);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        long id = read(response).id();
        assertThat(jdbcTemplate.queryForObject("SELECT CHAR_LENGTH(title) FROM items WHERE id = ?", Integer.class, id))
                .isEqualTo(100);
        assertThat(storedColumn(id, "title")).isEqualTo(title);
    }

    @Test
    @DisplayName("내용이 이모지 5000자면 201이고 그대로 저장된다")
    void emojiContentOfMaxLengthIsCreated() throws Exception {
        String content = "😀".repeat(5000);
        Map<String, Object> body = validBody();
        body.put("content", content);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "content")).isEqualTo(content);
    }

    @Test
    @DisplayName("내용이 정확히 5000자(한글)면 201이고 그대로 저장된다")
    void contentOfMaxLengthIsCreated() throws Exception {
        String content = "나".repeat(5000);
        Map<String, Object> body = validBody();
        body.put("content", content);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "content")).isEqualTo(content);
    }

    @Test
    @DisplayName("멱등 키가 정확히 64자면 201이고 그대로 저장된다")
    void idempotencyKeyOfMaxLengthIsCreated() throws Exception {
        String key = "k".repeat(64);

        HttpResponse<String> response = postItem(userId, key, validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(storedColumn(read(response).id(), "idempotency_key")).isEqualTo(key);
    }

    @Test
    @DisplayName("가격이 0(나눔)이면 201")
    void zeroPriceIsCreated() throws Exception {
        Map<String, Object> body = validBody();
        body.put("price", 0L);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(read(response).price()).isZero();
    }

    @Test
    @DisplayName("가격이 Long 최대값이면 201이고 그대로 저장된다")
    void maxLongPriceIsCreated() throws Exception {
        Map<String, Object> body = validBody();
        body.put("price", Long.MAX_VALUE);

        HttpResponse<String> response = postItem(userId, newKey(), body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("SELECT price FROM items WHERE id = ?", Long.class,
                read(response).id())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("400을 받은 뒤 내용을 고쳐 같은 키로 다시 보내면 201 (키는 저장 성공 시에만 소비)")
    void keyIsNotConsumedByRejectedRequest() throws Exception {
        String key = newKey();
        Map<String, Object> withoutPrice = validBody();
        withoutPrice.remove("price");
        assertThat(postItem(userId, key, withoutPrice).statusCode()).isEqualTo(400);

        HttpResponse<String> fixed = postItem(userId, key, validBody());

        assertThat(fixed.statusCode()).isEqualTo(201);
        assertThat(countItems()).isEqualTo(1);
    }

    private String storedColumn(long itemId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM items WHERE id = ?", String.class, itemId);
    }

    private static ItemResponse read(HttpResponse<String> response) {
        return JSON.readValue(response.body(), ItemResponse.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
