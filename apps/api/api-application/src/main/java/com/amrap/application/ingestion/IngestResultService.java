package com.amrap.application.ingestion;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import com.amrap.domain.result.Result;
import com.amrap.persistence.result.ResultRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestResultService {

    private static final Logger log = LoggerFactory.getLogger(IngestResultService.class);
    private static final DateTimeFormatter ITERATION_ID = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ResultRepository resultRepository;
    private final RagProperties properties;

    public IngestResultService(ResultRepository resultRepository, RagProperties properties) {
        this.resultRepository = resultRepository;
        this.properties = properties;
    }

    @Transactional
    public Result ingest(IngestResultCommand cmd, UUID submittedBy) {
        validate(cmd);
        String category = ResultConstraints.optionalLabel("category", cmd.category());
        String subCategory = ResultConstraints.optionalLabel("subCategory", cmd.subCategory());
        ResultConstraints.optionalPercentage("confidenceAccuracy", cmd.confidenceAccuracy());
        ResultConstraints.optionalPercentage("sensitivity", cmd.sensitivity());
        ResultConstraints.optionalPercentage("specificity", cmd.specificity());
        ResultConstraints.optionalPercentage("f1Score", cmd.f1Score());
        ResultConstraints.optionalPercentage("recall", cmd.recall());
        ResultConstraints.optionalPercentage("precision", cmd.precision());

        UUID id = UUID.randomUUID();
        Path storageRoot = Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
        String relativePath = id + ".json";
        Path absolute = storageRoot.resolve(relativePath);

        try {
            Files.createDirectories(storageRoot);
            byte[] bytes = cmd.rawJson().getBytes(StandardCharsets.UTF_8);
            Files.write(absolute, bytes);

            Result result = new Result();
            result.setId(id);
            result.setModelName(cmd.modelName().trim());
            result.setVersion(cmd.version().trim());
            result.setIterationId(ITERATION_ID.format(ZonedDateTime.now(properties.zoneId())));
            result.setCategory(category);
            result.setSubCategory(subCategory);
            result.setConfidenceAccuracy(cmd.confidenceAccuracy());
            result.setSensitivity(cmd.sensitivity());
            result.setSpecificity(cmd.specificity());
            result.setF1Score(cmd.f1Score());
            result.setRecall(cmd.recall());
            result.setPrecision(cmd.precision());
            result.setJsonPath(relativePath);
            result.setSizeBytes(bytes.length);
            result.setContentHash(sha256Hex(bytes));
            result.setInsertedAt(Instant.now());
            result.setVectorized(false);
            result.setCreatedBy(submittedBy);
            Result saved = resultRepository.save(result);

            log.info(
                    "Ingested result id={} model={} version={} iteration={} bytes={} path={}",
                    saved.getId(),
                    saved.getModelName(),
                    saved.getVersion(),
                    saved.getIterationId(),
                    saved.getSizeBytes(),
                    relativePath);
            return saved;
        } catch (IOException ex) {
            log.error("Failed to write result file {}", absolute, ex);
            throw new AppException(500, "Failed to persist result payload");
        }
    }

    private void validate(IngestResultCommand cmd) {
        if (cmd == null) {
            throw new AppException(400, "Payload is required");
        }
        if (cmd.modelName() == null || cmd.modelName().isBlank()) {
            throw new AppException(400, "modelName is required");
        }
        if (cmd.version() == null || cmd.version().isBlank()) {
            throw new AppException(400, "version is required");
        }
        if (cmd.rawJson() == null || cmd.rawJson().isBlank()) {
            throw new AppException(400, "payload JSON is required");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
