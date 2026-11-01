import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 1,000 users x 10 posts, distinct keys, all released at once by a latch. Args: app firstUserId */
public class Burst {
    public static void main(String[] args) throws Exception {
        boolean withDesign = args[0].equals("with-design");
        long firstUser = Long.parseLong(args[1]);
        String url = withDesign ? "http://localhost:8081/posts" : "http://localhost:8082/api/posts";
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(60)).build();
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10_000);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < 1000; u++) {
                for (int p = 0; p < 10; p++) {
                    long user = firstUser + u;
                    String key = UUID.randomUUID().toString();
                    String body = withDesign
                            ? "{\"title\":\"burst " + p + "\",\"content\":\"c\",\"price\":1000,\"negotiable\":false,\"categoryId\":1}"
                            : "{\"sellerId\":" + user + ",\"title\":\"burst " + p + "\",\"content\":\"c\",\"price\":1000,\"location\":\"서울\"}";
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json").header("Idempotency-Key", key)
                            .POST(HttpRequest.BodyPublishers.ofString(body));
                    if (withDesign) b.header("X-User-Id", Long.toString(user));
                    HttpRequest request = b.build();
                    pool.submit(() -> {
                        try {
                            start.await();
                            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                            outcomes.computeIfAbsent(Integer.toString(status), k -> new AtomicInteger()).incrementAndGet();
                        } catch (Exception e) {
                            outcomes.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            long t0 = System.nanoTime();
            start.countDown();
            done.await();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("elapsed_ms=" + ms + " outcomes=" + new TreeMap<>(outcomes));
        }
    }
}
