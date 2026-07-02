package dev.jbringb.resume_scope.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MonthlyUsageResponse(
        OffsetDateTime periodStart,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal estimatedCostEur,
        BigDecimal monthlyBudgetEur,
        boolean budgetExceeded,
        String apiKeyName) {}
