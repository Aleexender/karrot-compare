package com.example.karrot.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.karrot.item.presentation.dto.ItemResponse;
import com.example.karrot.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class PostConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final int REQUESTS = 20;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = createUser();
    }

    @RepeatedTest(5)
    @DisplayName("같은 유저·같은 키로 20번 동시에 등록하면 1건만 저장되고 201 1건 + 200 19건, 5xx 0건")
    void concurrentSameKeyCreatesOnlyOne() throws Exception {
        String key = UUID.randomUUID().toString();

        List<HttpResponse<String>> responses = postConcurrently(Collections.nCopies(REQUESTS, key));

        assertThat(statusCounts(responses)).containsExactlyInAnyOrderEntriesOf(Map.of(201, 1L, 200, (long) REQUESTS - 1));
        assertThat(responses.stream().map(response -> read(response).id()).distinct()).hasSize(1);
        assertThat(countItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 유저가 서로 다른 키로 20번 동시에 등록하면 20건 모두 201로 저장된다")
    void concurrentDifferentKeysCreatesAll() throws Exception {
        List<String> keys = IntStream.range(0, REQUESTS).mapToObj(i -> UUID.randomUUID().toString()).toList();

        List<HttpResponse<String>> responses = postConcurrently(keys);

        assertThat(statusCounts(responses)).containsExactlyEntriesOf(Map.of(201, (long) REQUESTS));
        assertThat(responses.stream().map(response -> read(response).id()).distinct()).hasSize(REQUESTS);
        assertThat(countItems()).isEqualTo(REQUESTS);
    }

    private List<HttpResponse<String>> postConcurrently(List<String> keys) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(keys.size());
        CountDownLatch ready = new CountDownLatch(keys.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String key : keys) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postItem(userId, key, validBody());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<Integer, Long> statusCounts(List<HttpResponse<String>> responses) {
        return responses.stream().collect(Collectors.groupingBy(HttpResponse::statusCode, Collectors.counting()));
    }

    private static ItemResponse read(HttpResponse<String> response) {
        return JSON.readValue(response.body(), ItemResponse.class);
    }
}
