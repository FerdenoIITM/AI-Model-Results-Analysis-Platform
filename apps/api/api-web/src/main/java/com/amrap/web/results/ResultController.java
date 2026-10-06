package com.amrap.web.results;

import com.amrap.application.common.AppException;
import com.amrap.application.ingestion.IngestResultCommand;
import com.amrap.application.ingestion.IngestResultService;
import com.amrap.application.ingestion.ResultFilterParser;
import com.amrap.application.ingestion.ResultQueryService;
import com.amrap.application.rag.ReindexService;
import com.amrap.domain.result.Result;
import com.amrap.domain.user.UserRole;
import com.amrap.security.AuthenticatedUser;
import com.amrap.security.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/results")
public class ResultController {

    private final IngestResultService ingestService;
    private final ResultQueryService queryService;
    private final ReindexService reindexService;
    private final ObjectMapper objectMapper;

    public ResultController(
            IngestResultService ingestService,
            ResultQueryService queryService,
            ReindexService reindexService,
            ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.queryService = queryService;
        this.reindexService = reindexService;
        this.objectMapper = objectMapper;
    }

    /**
     * Machine ingest. Expected auth: `X-Ingest-Key` (synthetic superadmin) or a superadmin JWT.
     * Admin and member JWTs are rejected. The raw JSON of `payload` is persisted verbatim on disk.
     */
    @PostMapping
    public ResponseEntity<ResultSummary> ingest(@Valid @RequestBody CreateResultRequest request) {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        if (actor.getRole() != UserRole.SUPERADMIN) {
            throw new AppException(403, "Only superadmin can submit results");
        }
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException ex) {
            throw new AppException(400, "payload must be valid JSON");
        }
        Result saved = ingestService.ingest(
                new IngestResultCommand(
                        request.modelName(),
                        request.version(),
                        rawJson,
                        request.category(),
                        request.subCategory(),
                        request.confidenceAccuracy(),
                        request.sensitivity(),
                        request.specificity(),
                        request.f1Score(),
                        request.recall(),
                        request.precision()),
                actor.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResultSummary.from(saved));
    }

    @GetMapping
    public ResponseEntity<ResultPageResponse> list(HttpServletRequest request) {
        SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(ResultPageResponse.from(
                queryService.search(ResultFilterParser.parseList(request.getParameterMap()))));
    }

    @GetMapping("/filter-options")
    public ResponseEntity<FilterOptionsResponse> filterOptions(HttpServletRequest request) {
        SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(FilterOptionsResponse.from(
                queryService.filterOptions(ResultFilterParser.parseCategories(request.getParameterMap()))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResultSummary> get(@PathVariable("id") UUID id) {
        SecurityUtils.requireCurrentUser();
        return ResponseEntity.ok(ResultSummary.from(queryService.requireById(id)));
    }

    @GetMapping(value = "/{id}/payload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> payload(@PathVariable("id") UUID id) {
        SecurityUtils.requireCurrentUser();
        Result r = queryService.requireById(id);
        return ResponseEntity.ok(queryService.readPayload(r));
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
        if (actor.getRole() != UserRole.SUPERADMIN) {
            throw new AppException(403, "Only superadmin can trigger reindex");
        }
        int reset = reindexService.resetAll();
        return ResponseEntity.ok(Map.of(
                "reset", reset,
                "message", "All results marked unvectorized. Worker will re-embed."));
    }
}
