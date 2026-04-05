package com.nium.cardplatform.shared.config;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.kafka")
public class AuditKafkaProperties {

    private final Topics topics = new Topics();

    /** Number of partitions for the primary audit topic. Default 3. */
    private int auditTopicPartitions = 12;

    /** Replication factor for the primary audit topic. Default 1. */
    private int auditTopicReplicas = 1;

    /** Number of partitions for the Dead Letter Topic. Default 1. */
    private int dltPartitions = 1;

    /** Replication factor for the Dead Letter Topic. Default 1. */
    private int dltReplicas = 1;


    @Getter
    @Setter
    public static class Topics {
        /** Topic name for audit events. */
        private String auditEvents = "card-audit-events";
    }
}
