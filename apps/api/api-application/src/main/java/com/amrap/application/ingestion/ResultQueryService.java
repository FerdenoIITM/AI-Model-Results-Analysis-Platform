package com.amrap.application.ingestion;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import com.amrap.domain.result.Result;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultPage;
import com.amrap.persistence.result.ResultRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side helpers for the Results page. */
@Service
public class ResultQueryService {

    private final ResultRepository resultRepository;
    private final RagProperties properties;

    public ResultQueryService(ResultRepository resultRepository, RagProperties properties) {
        this.resultRepository = resultRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ResultPage search(ResultFilter filter) {
        return resultRepository.search(filter);
    }

    @Transactional(readOnly = true)
    public FilterOptions filterOptions(List<String> categories) {
        List<String> allCategories = resultRepository.findDistinctCategories();
        List<String> subCategories = categories.isEmpty()
                ? resultRepository.findDistinctSubCategories()
                : resultRepository.findDistinctSubCategoriesByCategories(categories);
        return new FilterOptions(resultRepository.findDistinctModelNames(), allCategories, subCategories);
    }

    @Transactional(readOnly = true)
    public Result requireById(UUID id) {
        return resultRepository
                .findById(id)
                .orElseThrow(() -> new AppException(404, "Result not found"));
    }

    public String readPayload(Result result) {
        Path base = Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
        Path absolute = base.resolve(result.getJsonPath()).normalize();
        if (!absolute.startsWith(base)) {
            throw new AppException(500, "Refusing to read file outside storage dir");
        }
        try {
            return Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AppException(500, "Failed to read result payload");
        }
    }
}
