package com.amrap.persistence;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "com.amrap.persistence")
@EnableJpaRepositories(basePackages = "com.amrap.persistence")
public class PersistenceConfig {
}
