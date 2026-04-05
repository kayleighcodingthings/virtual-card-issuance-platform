package com.nium.cardplatform.transaction.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    // --- Requests ---

    @Builder
    public record DebitRequest(
            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.01", message = "amount must be greater than zero")
            @Digits(integer = 15, fraction = 2, message = "amount must have at most 2 decimal places")
            BigDecimal amount
    ) {
    }

    @Builder
    public record CreditRequest(
            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.01", message = "amount must be greater than zero")
            @Digits(integer = 15, fraction = 2, message = "amount must have at most 2 decimal places")
            BigDecimal amount
    ) {
    }

    // --- Responses ---

    @Builder
    public record TransactionResponse(
            UUID id,
            UUID cardId,
            String type,
            BigDecimal amount,
            String status,
            String declineReason,
            String idempotencyKey,
            LocalDateTime createdAt
    ) {
    }

    @Builder
    public record PagedTransactionResponse(
            List<TransactionResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean last
    ) {
    }
}
