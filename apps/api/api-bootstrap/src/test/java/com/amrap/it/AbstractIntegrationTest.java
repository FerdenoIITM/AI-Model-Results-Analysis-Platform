package com.amrap.it;

import com.amrap.application.query.OllamaChatClient;
import com.amrap.domain.user.UserRole;
import com.amrap.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots the real application against a throwaway Postgres + pgvector container that is
 * initialised from the production migrations in infra/db/migrations. Ollama-backed beans are
 * mocked; everything else (security, JPA, validation, file storage) is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    public static final String INGEST_KEY = "it-ingest-key";
    public static final String PASSWORD = "Password123!";
    /** Matches the default password created by V5__chat_readonly.sql. */
    public static final String CHAT_DB_PASSWORD = "amrap-chat-ro-change-me";
    protected static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final Path STORAGE;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("amrap")
                .withUsername("amrap")
                .withPassword("amrap-it");
        try (Stream<Path> files = Files.list(repoRoot().resolve("infra/db/migrations"))) {
            // Only .sql — V5 creates the role; V5b.sh is optional password sync for host/Compose.
            files.filter(f -> f.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .forEach(f -> POSTGRES.withCopyFileToContainer(
                            MountableFile.forHostPath(f), "/docker-entrypoint-initdb.d/" + f.getFileName()));
            STORAGE = Files.createTempDirectory("amrap-it-results");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("amrap.chat.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("amrap.chat.datasource.username", () -> "amrap_chat_ro");
        registry.add("amrap.chat.datasource.password", () -> CHAT_DB_PASSWORD);
        registry.add("amrap.rag.storage-dir", STORAGE::toString);
        registry.add("amrap.rag.ingest-api-key", () -> INGEST_KEY);
        registry.add("amrap.rag.worker-delay-ms", () -> "3600000");
        registry.add("amrap.security.jwt-secret", () -> "integration-test-secret-at-least-32-characters-long");
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:9");
        registry.add("amrap.chat.ollama-base-url", () -> "http://127.0.0.1:9");
    }

    @MockBean
    protected VectorStore vectorStore;

    @MockBean
    protected ChatModel chatModel;

    @MockBean
    protected EmbeddingModel embeddingModel;

    @MockBean
    protected OllamaChatClient ollamaChatClient;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected ObjectMapper json;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE users, results, result_chunks CASCADE");
    }

    protected TestUser createUser(UserRole role, UUID createdBy) {
        return createUser(role, createdBy, role.toDbValue() + "-" + UUID.randomUUID() + "@test.io", true);
    }

    protected TestUser createUser(UserRole role, UUID createdBy, String email, boolean active) {
        String name = role.name().charAt(0) + role.name().substring(1).toLowerCase() + " User";
        UUID id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, role, display_name, created_by, is_active)
                VALUES (?, ?, ?::user_role, ?, ?, ?) RETURNING id
                """, UUID.class, email, PASSWORD_HASH, role.toDbValue(), name, createdBy, active);
        return new TestUser(id, email, role, jwtService.generateToken(id, email, role, name));
    }

    protected UUID insertResult(ResultRow row) {
        return jdbc.queryForObject("""
                INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash,
                                     inserted_at, is_vectorized, category, sub_category, confidence_accuracy,
                                     sensitivity, specificity, f1_score, recall, precision)
                VALUES (?, ?, ?, 'x.json', 2, 'hash', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, row.model(), row.version(), row.iteration(), Timestamp.from(row.insertedAt()),
                row.vectorized(), row.category(), row.subCategory(), row.confidence(), row.sensitivity(),
                row.specificity(), row.f1(), row.recall(), row.precision());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected static String bearer(TestUser user) {
        return "Bearer " + user.token();
    }

    static Path repoRoot() {
        String configured = System.getProperty("amrap.repo.root");
        Path start = configured != null ? Paths.get(configured) : Paths.get("").toAbsolutePath();
        for (Path p = start.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("infra/db/migrations"))) {
                return p;
            }
        }
        throw new IllegalStateException("Cannot locate infra/db/migrations from " + start);
    }

    public record TestUser(UUID id, String email, UserRole role, String token) {
    }

    public record ResultRow(
            String model, String version, String iteration, Instant insertedAt, boolean vectorized,
            String category, String subCategory, Double confidence, Double sensitivity, Double specificity,
            Double f1, Double recall, Double precision) {

        public static ResultRow of(String model, String category, String subCategory, Double confidence, Instant at) {
            return new ResultRow(model, "1.0", "20260101000000", at, false, category, subCategory, confidence,
                    null, null, null, null, null);
        }

        public ResultRow withMetrics(Double sensitivity, Double specificity, Double f1, Double recall, Double precision) {
            return new ResultRow(model, version, iteration, insertedAt, vectorized, category, subCategory, confidence,
                    sensitivity, specificity, f1, recall, precision);
        }

        public ResultRow withVersion(String newVersion) {
            return new ResultRow(model, newVersion, iteration, insertedAt, vectorized, category, subCategory, confidence,
                    sensitivity, specificity, f1, recall, precision);
        }

        public ResultRow vectorized(boolean value) {
            return new ResultRow(model, version, iteration, insertedAt, value, category, subCategory, confidence,
                    sensitivity, specificity, f1, recall, precision);
        }
    }
}
