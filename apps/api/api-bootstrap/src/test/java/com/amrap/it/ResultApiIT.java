package com.amrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ResultApiIT extends AbstractIntegrationTest {

    private static final String VALID = """
            {"modelName":"CardioNet","version":"1.2.0","category":"Cardiology","subCategory":"Valve",
             "confidenceAccuracy":97.5,"sensitivity":94.2,"specificity":98.1,"f1Score":95.8,"recall":94.2,
             "precision":97.3,"payload":{"dataset":"echo","epochs":40}}
            """;

    private TestUser superadmin;
    private TestUser admin;
    private TestUser member;

    @BeforeEach
    void users() {
        superadmin = createUser(UserRole.SUPERADMIN, null);
        admin = createUser(UserRole.ADMIN, superadmin.id());
        member = createUser(UserRole.MEMBER, admin.id());
    }

    private ResultActions ingest(String body, String headerName, String headerValue) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/results").contentType(MediaType.APPLICATION_JSON).content(body);
        if (headerName != null) {
            req.header(headerName, headerValue);
        }
        return mvc.perform(req);
    }

    private ResultActions ingestWithKey(String body) throws Exception {
        return ingest(body, "X-Ingest-Key", INGEST_KEY);
    }

    @Nested
    class Ingest {

        @Test
        void ingestKeyCreatesResultFileAndRow() throws Exception {
            MvcResult result = ingestWithKey(VALID)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.modelName").value("CardioNet"))
                    .andExpect(jsonPath("$.version").value("1.2.0"))
                    .andExpect(jsonPath("$.category").value("Cardiology"))
                    .andExpect(jsonPath("$.confidenceAccuracy").value(97.5))
                    .andExpect(jsonPath("$.vectorized").value(false))
                    .andExpect(jsonPath("$.createdBy").doesNotExist())
                    .andExpect(jsonPath("$.iterationId").value(org.hamcrest.Matchers.matchesPattern("\\d{14}")))
                    .andReturn();

            JsonNode body = body(result);
            UUID id = UUID.fromString(body.get("id").asText());
            assertThat(body.get("jsonPath").asText()).isEqualTo(id + ".json");
            assertThat(Files.readString(STORAGE.resolve(id + ".json"))).isEqualTo("{\"dataset\":\"echo\",\"epochs\":40}");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM results WHERE id = ?", Integer.class, id)).isOne();
            assertThat(jdbc.queryForObject("SELECT created_by FROM results WHERE id = ?", UUID.class, id)).isNull();
        }

        @Test
        void superadminTokenRecordsCreator() throws Exception {
            MvcResult result = ingest(VALID, "Authorization", bearer(superadmin)).andExpect(status().isCreated()).andReturn();

            assertThat(body(result).get("createdBy").asText()).isEqualTo(superadmin.id().toString());
        }

        @Test
        void adminAndMemberTokensAreForbidden() throws Exception {
            ingest(VALID, "Authorization", bearer(admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Only superadmin can submit results"));
            ingest(VALID, "Authorization", bearer(member)).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM results", Integer.class)).isZero();
        }

        @Test
        void missingOrWrongCredentialsAreUnauthorized() throws Exception {
            ingest(VALID, null, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
            ingest(VALID, "X-Ingest-Key", "wrong-key").andExpect(status().isUnauthorized());
            ingest(VALID, "Authorization", "Bearer not-a-token").andExpect(status().isUnauthorized());
        }

        @Test
        void optionalFieldsMayBeOmittedOrNull() throws Exception {
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"category\":null,\"payload\":[1,2,3]}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.category").doesNotExist())
                    .andExpect(jsonPath("$.precision").doesNotExist());
        }

        @Test
        void boundaryMetricsAreAccepted() throws Exception {
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"confidenceAccuracy\":0,\"recall\":100,\"payload\":{}}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.confidenceAccuracy").value(0.0))
                    .andExpect(jsonPath("$.recall").value(100.0));
        }

        @Test
        void duplicateSubmissionsCreateSeparateRows() throws Exception {
            ingestWithKey(VALID).andExpect(status().isCreated());
            ingestWithKey(VALID).andExpect(status().isCreated());

            assertThat(jdbc.queryForObject("SELECT count(DISTINCT id) FROM results", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT content_hash) FROM results", Integer.class)).isOne();
        }

        @Test
        void missingRequiredFieldsAreBadRequest() throws Exception {
            ingestWithKey("{\"version\":\"1\",\"payload\":{}}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
            ingestWithKey("{\"modelName\":\"M\",\"payload\":{}}").andExpect(status().isBadRequest());
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\"}").andExpect(status().isBadRequest());
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"payload\":null}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("payload is required"));
            ingestWithKey("{\"modelName\":\"  \",\"version\":\"1\",\"payload\":{}}").andExpect(status().isBadRequest());
            ingestWithKey("{}").andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM results", Integer.class)).isZero();
        }

        @Test
        void wrongTypesAndMalformedJsonAreBadRequest() throws Exception {
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"recall\":\"high\",\"payload\":{}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Request body is invalid"));
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"recall\":[1],\"payload\":{}}").andExpect(status().isBadRequest());
            ingestWithKey("{\"modelName\":\"M\",").andExpect(status().isBadRequest());
            ingestWithKey("").andExpect(status().isBadRequest());
            ingestWithKey("[]").andExpect(status().isBadRequest());
        }

        @ParameterizedTest
        @ValueSource(strings = {"-1", "100.5", "1000"})
        void outOfRangeMetricsAreBadRequest(String value) throws Exception {
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"f1Score\":" + value + ",\"payload\":{}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("f1Score must be between 0 and 100"));
        }

        @Test
        void overLongOrBlankLabelsAreBadRequest() throws Exception {
            ingestWithKey("{\"modelName\":\"" + "m".repeat(256) + "\",\"version\":\"1\",\"payload\":{}}")
                    .andExpect(status().isBadRequest());
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"" + "v".repeat(101) + "\",\"payload\":{}}")
                    .andExpect(status().isBadRequest());
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"category\":\" \",\"payload\":{}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("category must not be blank"));
            ingestWithKey("{\"modelName\":\"M\",\"version\":\"1\",\"subCategory\":\"" + "s".repeat(121) + "\",\"payload\":{}}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unsupportedContentTypeIs415() throws Exception {
            mvc.perform(post("/api/results").header("X-Ingest-Key", INGEST_KEY)
                            .contentType(MediaType.TEXT_PLAIN).content(VALID))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.status").value(415));
        }
    }

    @Nested
    class ListAndFilter {

        private final Instant t0 = Instant.parse("2026-02-01T00:00:00Z");

        @BeforeEach
        void seed() {
            insertResult(ResultRow.of("CardioNet", "Cardiology", "Valve", 97.5, t0.plusSeconds(1)).withMetrics(94.0, 98.0, 95.0, 94.0, 97.0));
            insertResult(ResultRow.of("CardioNet", "Cardiology", "Rhythm", 88.0, t0.plusSeconds(2)));
            insertResult(ResultRow.of("ChestScan", "Radiology", "Lung", 95.0, t0.plusSeconds(3)));
            insertResult(ResultRow.of("NeuroSlice", "Neurology", "Tumor", 10.0, t0.plusSeconds(4)));
        }

        private ResultActions list(String query, TestUser as) throws Exception {
            return mvc.perform(get("/api/results" + query).header("Authorization", bearer(as)));
        }

        @Test
        void responseShapeAndDefaults() throws Exception {
            list("", member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(4))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(50))
                    .andExpect(jsonPath("$.results", hasSize(4)))
                    .andExpect(jsonPath("$.results[0].modelName").value("NeuroSlice"))
                    .andExpect(jsonPath("$.results[0].id").exists())
                    .andExpect(jsonPath("$.results[0].insertedAt").exists());
        }

        @Test
        void everyRoleCanList() throws Exception {
            for (TestUser user : new TestUser[] {superadmin, admin, member}) {
                list("", user).andExpect(status().isOk());
            }
        }

        @Test
        void unauthenticatedListIs401() throws Exception {
            mvc.perform(get("/api/results")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/results/filter-options")).andExpect(status().isUnauthorized());
        }

        @Test
        void filtersCombineAndSortNumerically() throws Exception {
            list("?model=CardioNet&model=ChestScan&confidenceAccuracyGte=90&sort=-confidenceAccuracy", member)
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.results[0].confidenceAccuracy").value(97.5))
                    .andExpect(jsonPath("$.results[1].confidenceAccuracy").value(95.0));
            list("?sort=confidenceAccuracy", member)
                    .andExpect(jsonPath("$.results[0].confidenceAccuracy").value(10.0))
                    .andExpect(jsonPath("$.results[3].confidenceAccuracy").value(97.5));
            list("?category=Cardiology&subCategory=Valve&sensitivityGt=90&precisionLte=97", member)
                    .andExpect(jsonPath("$.total").value(1));
            list("?confidenceAccuracyEq=88", member).andExpect(jsonPath("$.results[0].subCategory").value("Rhythm"));
            list("?confidenceAccuracyGt=10&confidenceAccuracyLt=95", member).andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void noMatchesReturnsEmptyPage() throws Exception {
            list("?model=DoesNotExist", member)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.results", hasSize(0)));
        }

        @Test
        void pagingBeyondLastPageIsEmptyButKeepsTotal() throws Exception {
            list("?pageSize=3&page=2", member).andExpect(jsonPath("$.results", hasSize(1))).andExpect(jsonPath("$.total").value(4));
            list("?pageSize=3&page=9", member).andExpect(jsonPath("$.results", hasSize(0))).andExpect(jsonPath("$.total").value(4));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "?version=1.0", "?modelName=CardioNet", "?limit=5", "?confidenceAccuracyGte=abc",
                "?confidenceAccuracyGte=101", "?confidenceAccuracyGte=98&confidenceAccuracyLte=90",
                "?recallGt=50&recallLt=50", "?recallEq=5&recallGt=1", "?recallGt=1&recallGte=2", "?pageSize=101",
                "?pageSize=0", "?page=0", "?page=x", "?sort=contentHash", "?vectorized=maybe", "?model=",
                "?subCategory=", "?confidenceAccuracyGte=", "?confidenceAccuracyGte=NaN"})
        void invalidQueriesAreBadRequestWithMessage(String query) throws Exception {
            list(query, member)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void filterOptionsComeFromDatabase() throws Exception {
            mvc.perform(get("/api/results/filter-options").header("Authorization", bearer(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.models[0]").value("CardioNet"))
                    .andExpect(jsonPath("$.models", hasSize(3)))
                    .andExpect(jsonPath("$.categories", hasSize(3)))
                    .andExpect(jsonPath("$.subCategories", hasSize(4)))
                    .andExpect(jsonPath("$.versions").doesNotExist());
            mvc.perform(get("/api/results/filter-options?category=Cardiology").header("Authorization", bearer(member)))
                    .andExpect(jsonPath("$.categories", hasSize(3)))
                    .andExpect(jsonPath("$.subCategories[0]").value("Rhythm"))
                    .andExpect(jsonPath("$.subCategories[1]").value("Valve"))
                    .andExpect(jsonPath("$.subCategories", hasSize(2)));
            mvc.perform(get("/api/results/filter-options?model=CardioNet").header("Authorization", bearer(member)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void newlyIngestedModelAppearsInFilterOptions() throws Exception {
            ingestWithKey(VALID.replace("CardioNet", "BrandNewNet")).andExpect(status().isCreated());

            mvc.perform(get("/api/results/filter-options").header("Authorization", bearer(member)))
                    .andExpect(content().string(containsString("BrandNewNet")));
        }
    }

    @Nested
    class SingleResult {

        @Test
        void getAndPayloadRoundTrip() throws Exception {
            String id = body(ingestWithKey(VALID).andReturn()).get("id").asText();

            mvc.perform(get("/api/results/" + id).header("Authorization", bearer(member)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));
            mvc.perform(get("/api/results/" + id + "/payload").header("Authorization", bearer(member)))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"dataset\":\"echo\",\"epochs\":40}", true));
        }

        @Test
        void unknownIdIs404() throws Exception {
            String id = UUID.randomUUID().toString();
            mvc.perform(get("/api/results/" + id).header("Authorization", bearer(member)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Result not found"));
            mvc.perform(get("/api/results/" + id + "/payload").header("Authorization", bearer(member)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void malformedIdIs400() throws Exception {
            mvc.perform(get("/api/results/not-a-uuid").header("Authorization", bearer(member)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        void missingPayloadFileIs500WithSafeMessage() throws Exception {
            UUID id = insertResult(ResultRow.of("M", null, null, null, Instant.now()));

            mvc.perform(get("/api/results/" + id + "/payload").header("Authorization", bearer(member)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Failed to read result payload"));
        }
    }

    @Nested
    class Reindex {

        @Test
        void superadminResetsAllRowsAndClearsChunks() throws Exception {
            insertResult(ResultRow.of("M", null, null, null, Instant.now()).vectorized(true));
            insertResult(ResultRow.of("N", null, null, null, Instant.now()).vectorized(true));
            jdbc.update("INSERT INTO result_chunks (id, content, metadata) VALUES (gen_random_uuid(), 'c', '{}'::jsonb)");

            mvc.perform(post("/api/results/reindex").header("Authorization", bearer(superadmin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reset").value(2))
                    .andExpect(jsonPath("$.message").isNotEmpty());

            assertThat(jdbc.queryForObject("SELECT count(*) FROM results WHERE is_vectorized", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM result_chunks", Integer.class)).isZero();
        }

        @Test
        void otherRolesForbiddenAndAnonymousUnauthorized() throws Exception {
            mvc.perform(post("/api/results/reindex").header("Authorization", bearer(admin)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Only superadmin can trigger reindex"));
            mvc.perform(post("/api/results/reindex").header("Authorization", bearer(member))).andExpect(status().isForbidden());
            mvc.perform(post("/api/results/reindex")).andExpect(status().isUnauthorized());
        }

        @Test
        void reindexOnEmptyDatabaseResetsZero() throws Exception {
            mvc.perform(post("/api/results/reindex").header("Authorization", bearer(superadmin)))
                    .andExpect(jsonPath("$.reset").value(0));
        }
    }
}
