package com.nium.cardplatform.shared.events;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal Spring ApplicationEvent published after successful operations.
 * Consumed by AuditKafkaPublisher which forwards them to Kafka.
 * <p>Using Spring events (not direct Kafka calls from services) decouples
 * the audit concern from business logic. If Kafka is unavailable the
 * business transaction still commits — audit is best-effort.
 */
@Getter
@Builder
public class CardAuditEvent {

    private final String eventType;
    private final UUID cardId;
    private final UUID transactionId;
    private final String detail;
    private final BigDecimal amount;
    private final LocalDateTime occurredAt;

    public static CardAuditEvent onCreateCard(UUID cardId, String cardholderName) {
        return CardAuditEvent.builder()
                .eventType("CARD_CREATED")
                .cardId(cardId)
                .detail("cardholder=" + cardholderName)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    public static CardAuditEvent statusChanged(UUID cardId, String from, String to) {
        return CardAuditEvent.builder()
                .eventType("CARD_STATUS_CHANGED")
                .cardId(cardId)
                .detail(from + " -> " + to)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    public static CardAuditEvent onExpireCard(UUID cardId) {
        return CardAuditEvent.builder()
                .eventType("CARD_EXPIRED")
                .cardId(cardId)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    public static CardAuditEvent onTransactionCompleted(UUID cardId, UUID transactionId, String type, BigDecimal amount) {
        return CardAuditEvent.builder()
                .eventType("TRANSACTION_" + type)
                .cardId(cardId)
                .transactionId(transactionId)
                .amount(amount)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
