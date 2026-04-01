package com.nium.cardplatform.card.api.dto;


import jakarta.validation.constraints.*;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class CardDtos {

    private CardDtos() {
    }

    // Requests

    @Builder
    public record CreateCardRequest(
            @NotBlank(message = "cardholderName is required")
            @Size(min = 2, max = 255)
            String cardholderName,

            @NotNull(message = "initialBalance is required")
            @DecimalMin(value = "0.00", message = "initialBalance cannot be negative")
            @Digits(integer = 15, fraction = 2)
            BigDecimal initialBalance,

            LocalDateTime expiresAt // nullable -> defaults to now()+2years
    ) {
    }

    // Responses

    @Builder
    public record CardResponse(
            UUID id,
            String cardholderName,
            BigDecimal balance,
            String status,
            LocalDateTime expiresAt,
            LocalDateTime createdAt
    ) {
    }
}
