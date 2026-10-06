package com.amrap.application.ingestion;

import java.util.List;

public record FilterOptions(List<String> categories, List<String> subCategories) {

    public FilterOptions {
        categories = List.copyOf(categories);
        subCategories = List.copyOf(subCategories);
    }
}
