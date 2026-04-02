package com.nium.cardplatform.audit.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The JSON payload written to and read from the Kafka audit topic.
 * Kept in the audit module so it owns its own schema.
 * Lombok {@link Data} generates equals/hashCode/toString used in tests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMessage {
    private String eventType;
    private UUID cardId;
    private UUID transactionId;
    private String detail;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
}

