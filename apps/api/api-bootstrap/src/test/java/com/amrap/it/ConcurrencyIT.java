package com.amrap.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.amrap.domain.user.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Real HTTP over the embedded server so requests genuinely race on separate threads. */
class ConcurrencyIT extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private List<HttpResponse<String>> race(int count, Callable<HttpRequest> requestFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                HttpRequest request = requestFactory.call();
                futures.add(pool.submit(() -> {
                    start.await();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelIngestsAreAllStoredWithoutLoss() throws Exception {
        int[] n = {0};
        List<HttpResponse<String>> responses = race(25, () -> HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/results"))
                .header("Content-Type", "application/json")
                .header("X-Ingest-Key", INGEST_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"modelName\":\"Race\",\"version\":\"1\",\"confidenceAccuracy\":" + (n[0]++) + ",\"payload\":{\"i\":" + n[0] + "}}"))
                .build());

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM results WHERE model_name='Race'", Integer.class)).isEqualTo(25);
        List<String> paths = jdbc.queryForList("SELECT json_path FROM results WHERE model_name='Race'", String.class);
        assertThat(paths).doesNotHaveDuplicates();
        for (String p : paths) {
            assertThat(Files.exists(STORAGE.resolve(p))).isTrue();
        }
    }

    @Test
    void concurrentCreatesWithSameEmailYieldExactlyOneUser() throws Exception {
        TestUser superadmin = createUser(UserRole.SUPERADMIN, null);
        for (int round = 0; round < 5; round++) {
            String email = "race-" + UUID.randomUUID() + "@test.io";
            List<HttpResponse<String>> responses = race(6, () -> HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", bearer(superadmin))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + email + "\",\"password\":\"password123\",\"displayName\":\"R\",\"role\":\"member\"}"))
                    .build());

            assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201, 409);
            assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email)).isOne();
        }
    }

    @Test
    void concurrentReadsDuringWritesStayConsistent() throws Exception {
        TestUser member = createUser(UserRole.MEMBER, null);
        for (int i = 0; i < 20; i++) {
            insertResult(ResultRow.of("Read", null, null, (double) i, java.time.Instant.now()));
        }

        List<HttpResponse<String>> responses = race(20, () -> HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/results?model=Read&pageSize=100"))
                .header("Authorization", bearer(member))
                .GET()
                .build());

        for (HttpResponse<String> r : responses) {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json.readTree(r.body()).get("total").asInt()).isEqualTo(20);
        }
    }
}
