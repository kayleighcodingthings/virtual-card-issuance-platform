package com.nium.cardplatform.transaction.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transaction lifecycle: PENDING → SUCCESSFUL or DECLINED.
 * <p>A PENDING record is written first, before the balance mutation commits.
 * This gives an observable intermediate state — useful for diagnosing
 * timeouts and partial failures in production. Once the balance operation
 * succeeds the record is promoted to SUCCESSFUL; on any failure it is
 * updated to DECLINED with a reason code.
 * <p>Declined transactions are always persisted for full auditability.
 */
@Entity
@Table(name = "transaction")
@Getter
@Setter(AccessLevel.PACKAGE)   // status + declineReason updatable within package
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "type", nullable = false, columnDefinition = "transaction_type")
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "transaction_status")
    private TransactionStatus status;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Factory methods

    /**
     * Creates the initial PENDING record written before any balance mutation.
     * Must be updated via {@link #acceptTransaction()} or {@link #declineTransaction(String)}
     */
    public static Transaction pending(UUID cardId, TransactionType type, BigDecimal amount, String idempotencyKey) {
        return Transaction.builder()
                .cardId(cardId)
                .type(type)
                .amount(amount)
                .status(TransactionStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    public static Transaction declined(UUID cardId, TransactionType type, BigDecimal amount, String reason, String idempotencyKey) {
        return Transaction.builder()
                .cardId(cardId)
                .type(type)
                .amount(amount)
                .status(TransactionStatus.DECLINED)
                .declineReason(reason)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    // State transitions

    /**
     * Transitions a PENDING transaction to SUCCESSFUL after the balance commits.
     */
    public void acceptTransaction() {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Can only accept a PENDING transaction, current=" + status);
        }

        this.status = TransactionStatus.SUCCESSFUL;
    }

    /**
     * Transitions a PENDING transaction to DECLINED with a reason code.
     */
    public void declineTransaction(String reason) {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Can only decline a PENDING transaction, current=" + status);
        }

        this.status = TransactionStatus.DECLINED;
        this.declineReason = reason;
    }
}
