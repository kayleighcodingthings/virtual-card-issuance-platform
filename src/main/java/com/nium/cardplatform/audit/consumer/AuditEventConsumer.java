package com.nium.cardplatform.audit.consumer;

import com.nium.cardplatform.audit.event.AuditMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes audit events from Kafka.
 * <p>{@link RetryableTopic} configuration:
 * <ul>
 *   <li>3 retry attempts with exponential back-off (500ms → 1s → 2s)</li>
 *   <li>On exhaustion the message lands in the Dead Letter Topic (DLT) - card-audit-events.DLT</li>
 *   <li>SUFFIX_WITH_INDEX_VALUE names retry topics - card-audit-events-retry-0, card-audit-events-retry-1,...</li>
 *   <li>autoCreateTopics=false: topics must be pre-created (done in KafkaConfig) to avoid accidental topic sprawl in production.</li>
 * </ul>
 * <p>DLT handler logs the failed message with full context so ops can replay
 * or alert on it. In production this would write to a dead_letter_audit_log table.
 */

@Slf4j
@Component
public class AuditEventConsumer {

    @RetryableTopic(
            attempts = "4",          // 1 original + 3 retries
            backoff = @Backoff(delay = 500, multiplier = 2, maxDelay = 4000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = "${app.kafka.topics.audit-events:card-audit-events}",
            groupId = "${spring.kafka.consumer.group-id:card-platform-audit}"
    )
    public void consume(AuditMessage message,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Audit event received: eventType={} cardId={} topic={} offset={}",
                message.getEventType(), message.getCardId(), topic, offset);

        processAuditEvent(message);
    }

    @DltHandler
    public void handleDlt(AuditMessage message,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                          @Header(KafkaHeaders.OFFSET) long offset) {
        // Dead Letter Topic handler — all retries exhausted.
        // Production actions: alert on-call, write to dead_letter_audit_log, trigger manual review.
        log.error("AUDIT DLT - message exhausted retries: eventType={} cardId={} topic={} offset={}",
                message.getEventType(), message.getCardId(), topic, offset);
    }

    private void processAuditEvent(AuditMessage message) {
        switch (message.getEventType()) {
            case "CARD_CREATED":
                log.debug("Processing CARD_CREATED event for cardId={}", message.getCardId());
            case "CARD_EXPIRED":
                log.debug("Processing CARD_EXPIRED event for cardId={}", message.getCardId());
            case "CARD_STATUS_CHANGED":
                log.debug("Processing CARD_STATUS_CHANGED event for cardId={}", message.getCardId());
            case "TRANSACTION_DEBIT", "TRANSACTION_CREDIT":
                log.debug("Processing {} event for cardId={} amount={}",
                        message.getEventType(), message.getCardId(), message.getAmount());
            default:
                log.warn("Unknown audit event type: eventType={} cardId={}",
                        message.getEventType(), message.getCardId());
        }
    }
}
