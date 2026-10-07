package com.amrap.web.results;

import com.amrap.application.ingestion.FilterOptions;
import java.util.List;

public record FilterOptionsResponse(List<String> models, List<String> categories, List<String> subCategories) {

    public static FilterOptionsResponse from(FilterOptions options) {
        return new FilterOptionsResponse(options.models(), options.categories(), options.subCategories());
    }
}
