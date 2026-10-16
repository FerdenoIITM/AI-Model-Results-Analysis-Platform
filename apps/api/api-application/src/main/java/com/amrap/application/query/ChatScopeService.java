package com.amrap.application.query;

import com.amrap.domain.result.Result;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultPage;
import com.amrap.persistence.result.ResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves chat UI multi-select filters to {@code results.id} values for RAG scoping.
 *
 * @return {@code null} when no UI filters are set; otherwise the matching ids (possibly empty)
 */
@Service
public class ChatScopeService {

    static final int MAX_IDS = 200;

    private final ResultRepository resultRepository;

    public ChatScopeService(ResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    public List<UUID> resolveResultIds(ChatRequest request) {
        if (request == null || !request.hasUiFilters()) {
            return null;
        }
        List<UUID> ids = new ArrayList<>();
        int page = 1;
        int pageSize = 100;
        while (ids.size() < MAX_IDS) {
            ResultFilter filter = new ResultFilter(
                    request.categories(),
                    request.subCategories(),
                    request.modelNames(),
                    null,
                    Map.of(),
                    "insertedAt",
                    true,
                    page,
                    pageSize);
            ResultPage resultPage = resultRepository.search(filter);
            List<Result> rows = resultPage.results();
            if (rows.isEmpty()) {
                break;
            }
            for (Result row : rows) {
                ids.add(row.getId());
                if (ids.size() >= MAX_IDS) {
                    break;
                }
            }
            if (rows.size() < pageSize) {
                break;
            }
            page++;
        }
        return List.copyOf(ids);
    }
}
