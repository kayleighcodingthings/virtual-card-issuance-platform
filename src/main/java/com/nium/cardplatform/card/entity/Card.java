package com.nium.cardplatform.card.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "card")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cardholder_name", nullable = false)
    private String cardholderName;

    /**
     * DECIMAL(19,4) — never float or double for money.
     * Scale-4 internally; API layer strips trailing zeros on output.
     */
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "card_status")
    private CardStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Optimistic Locking version counter which JPA increments on every UPDATE.
     * If two concurrent transactions both read version = N, and both try to write, the second transaction throws
     * {@link OptimisticLockException} which is caught & retried in {@link TransactionService}
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Domain Helpers

    public boolean isActive() {
        return this.status == CardStatus.ACTIVE;
    }

    public boolean isTerminated() {
        return this.status.isTerminated();
    }

    /**
     * Debits the card balance.
     *
     * @throws InsufficientFundsException if the resulting balance would be negative.
     */
    public void debit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive.");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }

        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive.");
        }

        this.balance = this.balance.add(amount);
    }

    public static class InsufficientFundsException extends RuntimeException {
        private final UUID cardId;
        private final BigDecimal available;
        private final BigDecimal requested;

        public InsufficientFundsException(UUID cardId, BigDecimal available, BigDecimal requested) {
            super(String.format("Insufficient funds on card %s: available balance:%s requested amount:%s", cardId, available, requested));

            this.cardId = cardId;
            this.available = available;
            this.requested = requested;
        }

        public UUID getCardId() {
            return cardId;
        }

        public BigDecimal getAvailable() {
            return available;
        }

        public BigDecimal getRequested() {
            return requested;
        }
    }

}
