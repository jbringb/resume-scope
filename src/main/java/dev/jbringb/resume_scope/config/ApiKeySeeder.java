package dev.jbringb.resume_scope.config;

import dev.jbringb.resume_scope.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds the {@code api_key} table from the legacy {@code security.api-key} (env {@code API_KEY})
 * config on startup, so existing deployments keep working without any manual SQL: the shared
 * secret becomes a row named "default" with no per-key budget override (falls back to the global
 * {@code analysis.cost.monthly-budget-eur}). Idempotent — safe to run on every boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeySeeder implements ApplicationRunner {

    private static final String DEFAULT_KEY_NAME = "default";

    private final ApiKeyRepository apiKeyRepo;

    @Value("${security.api-key:}")
    private String legacyApiKey;

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(legacyApiKey)) {
            log.warn("No API key configured (security.api-key / API_KEY unset) — /api/** is open.");
            return;
        }
        apiKeyRepo
                .ensureKeyExists(DEFAULT_KEY_NAME, ApiKeyHashing.sha256Hex(legacyApiKey), null)
                .block();
    }
}
