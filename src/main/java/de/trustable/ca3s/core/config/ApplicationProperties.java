package de.trustable.ca3s.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to Ca 3 S.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {
}
