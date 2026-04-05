package com.nium.cardplatform.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nium.cardplatform.audit.consumer.AuditEventConsumer;
import com.nium.cardplatform.audit.event.AuditMessage;
import com.nium.cardplatform.audit.publisher.AuditKafkaPublisher;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Application-wide bean configuration: Jackson, Micrometer, async execution, and Kafka topics.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AuditKafkaProperties.class)
@RequiredArgsConstructor
class AppConfig {

    private final AuditKafkaProperties auditKafkaProperties;

    // --- Jackson ---

    /**
     * Primary {@link ObjectMapper} with Java 8 time support and ISO-8601 date serialization.
     * <p>Registered as {@code @Primary} so Spring MVC and any other component that
     * autowires {@code ObjectMapper} gets this configured instance rather than
     * Spring Boot's auto-configured default.
     * <p>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} is disabled so
     * {@code LocalDateTime} values serialize as {@code "2024-01-15T10:30:00"} rather
     * than a numeric epoch array.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // --- Micrometer @Timed support ---

    /**
     * Enables {@link Timed} as an AOP annotation.
     * Without this bean, {@code @Timed} on service methods is silently ignored.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    // --- Async executor for audit event publishing ---

    /**
     * Dedicated thread pool for {@code @Async} audit event publishing in
     * {@link AuditKafkaPublisher}.
     * <p>Isolated from the common task executor so a slow or backlogged Kafka broker
     * cannot starve the main request-handling threads.
     * <p>{@code waitForTasksToCompleteOnShutdown=true} with a 20-second termination
     * window ensures in-flight audit publishes are not dropped during a graceful shutdown.
     * Queue capacity of 500 absorbs bursts without blocking callers.
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(5);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("audit-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(20);
        exec.initialize();
        return exec;
    }

    // --- Kafka topics (auto-created on startup if broker allows) ---

    /**
     * Declares the primary audit events topic.
     * <p>3 partitions allow up to 3 consumers in the audit consumer group to process
     * events in parallel. 1 replica is sufficient for a single-broker development setup;
     * production should use {@code replicas(3)} with {@code min.insync.replicas=2}.
     * <p>Topic creation is idempotent — if the topic already exists with compatible
     * settings, no action is taken.
     */
    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name(auditKafkaProperties.getTopics().getAuditEvents())
                .partitions(auditKafkaProperties.getAuditTopicPartitions())
                .replicas(auditKafkaProperties.getAuditTopicReplicas())
                .build();
    }

    /**
     * Declares the Dead Letter Topic for audit events that exhaust all retry attempts.
     * <p>1 partition is intentional — DLT throughput is expected to be negligible under
     * normal conditions, and ordering within the DLT aids manual investigation and replay.
     *
     * @see AuditEventConsumer#handleDlt
     */
    @Bean
    public NewTopic auditEventsDltTopic() {
        return TopicBuilder.name(auditKafkaProperties.getTopics().getAuditEvents() + ".DLT")
                .partitions(auditKafkaProperties.getDltPartitions())
                .replicas(auditKafkaProperties.getDltReplicas())
                .build();
    }
}
