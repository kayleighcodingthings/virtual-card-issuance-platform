package com.nium.cardplatform.audit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nium.cardplatform.audit.event.AuditMessage;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka producer configuration for the audit module.
 * <p>Provides a {@link KafkaTemplate} whose {@link JsonSerializer} uses the application's
 * {@link ObjectMapper} (with {@code JavaTimeModule} registered). Without this, Spring Kafka's
 * auto-configured producer creates its own {@code ObjectMapper} that lacks the module,
 * causing {@code LocalDateTime} to serialize as {@code [2026,4,5,22,14,9,...]} instead of
 * ISO-8601 {@code "2026-04-05T22:14:09"}.
 * <p>This bean lives in the {@code audit} module (not {@code shared}) because it depends on
 * {@link AuditMessage} - placing it in {@code shared} would create a cross-module dependency
 * that Spring Modulith would reject.
 */
@Configuration
class AuditKafkaConfig {

    @Bean
    public KafkaTemplate<String, AuditMessage> kafkaTemplate(
            KafkaProperties kafkaProperties,
            ObjectMapper objectMapper) {
        JsonSerializer<AuditMessage> valueSerializer = new JsonSerializer<>(objectMapper);
        ProducerFactory<String, AuditMessage> factory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null),
                new StringSerializer(),
                valueSerializer
        );
        return new KafkaTemplate<>(factory);
    }
}