package com.amrap.application.ingestion;

import java.util.List;

public record FilterOptions(List<String> models, List<String> categories, List<String> subCategories) {

    public FilterOptions {
        models = List.copyOf(models);
        categories = List.copyOf(categories);
        subCategories = List.copyOf(subCategories);
    }
}
