package com.nium.cardplatform.audit.publisher;

import com.nium.cardplatform.audit.event.AuditMessage;
import com.nium.cardplatform.shared.events.CardAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges Spring ApplicationEvents to Kafka.
 * <p>Why {@link TransactionalEventListener}(AFTER_COMMIT)?
 * The business transaction must commit successfully before we publish to Kafka.
 * Using AFTER_COMMIT ensures we never emit an audit event for a rolled-back operation.
 * If Kafka is unavailable, we log the error — audit is best-effort and must not
 * cause the business operation to fail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditKafkaPublisher {
    private final KafkaTemplate<String, AuditMessage> kafkaTemplate;

    @Value("${app.kafka.topics.audit-events:card-audit-events}")
    private String auditTopic;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuditEvent(CardAuditEvent event) {
        AuditMessage message = AuditMessage.builder()
                .eventType(event.getEventType())
                .cardId(event.getCardId())
                .transactionId(event.getTransactionId())
                .detail(event.getDetail())
                .amount(event.getAmount())
                .occurredAt(event.getOccurredAt())
                .build();

        try {
            kafkaTemplate.send(auditTopic, event.getCardId().toString(), message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish audit event: eventType={} cardId={} error={}",
                                    event.getEventType(), event.getCardId(), ex.getMessage());
                        } else {
                            log.debug("Audit event published: eventType={} cardId={} offset={}",
                                    event.getEventType(), event.getCardId(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("Unexpected error publishing audit event: eventType={} cardId={}",
                    event.getEventType(), event.getCardId(), e);
        }
    }
}
