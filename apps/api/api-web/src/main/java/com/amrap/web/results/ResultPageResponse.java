package com.amrap.web.results;

import com.amrap.domain.result.ResultPage;
import java.util.List;

public record ResultPageResponse(List<ResultSummary> results, long total, int page, int pageSize) {

    public static ResultPageResponse from(ResultPage page) {
        return new ResultPageResponse(
                page.results().stream().map(ResultSummary::from).toList(),
                page.total(),
                page.page(),
                page.pageSize());
    }
}
