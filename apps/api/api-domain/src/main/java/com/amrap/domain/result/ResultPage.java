package com.amrap.domain.result;

import java.util.List;

public record ResultPage(List<Result> results, long total, int page, int pageSize) {

    public ResultPage {
        results = List.copyOf(results);
    }
}
