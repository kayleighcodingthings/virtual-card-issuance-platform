package com.nium.cardplatform.shared;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared test factory for building domain objects in tests.
 * Centralises test data construction so changes to entity structure
 * only need updating in one place.
 */
public final class TestFactory {

    private TestFactory() {
    }

    // Card Builders

    public static Card activeCard(BigDecimal balance) {
        return Card.builder()
                .id(UUID.randomUUID())
                .cardholderName("Alice Smith")
                .balance(balance)
                .status(CardStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusYears(2))
                .version(0L)
                .build();
    }

    public static Card activeCard() {
        return activeCard(new BigDecimal("100.00"));
    }

    public static Card cardWithStatus(CardStatus status) {
        return Card.builder()
                .id(UUID.randomUUID())
                .cardholderName("Alice Smith")
                .balance(new BigDecimal("100.00"))
                .status(status)
                .expiresAt(LocalDateTime.now().plusYears(2))
                .version(0L)
                .build();
    }

    public static Card expiredCard() {
        return Card.builder()
                .id(UUID.randomUUID())
                .cardholderName("Alice Smith")
                .balance(BigDecimal.ZERO)
                .status(CardStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .version(0L)
                .build();
    }

    // Transaction Builders

    public static Transaction successfulDebit(UUID cardId, BigDecimal amount) {
        Transaction txn = Transaction.pending(cardId, TransactionType.DEBIT, amount,
                UUID.randomUUID().toString());
        txn.acceptTransaction();
        return txn;
    }

    public static Transaction successfulCredit(UUID cardId, BigDecimal amount) {
        Transaction txn = Transaction.pending(cardId, TransactionType.CREDIT, amount,
                UUID.randomUUID().toString());
        txn.acceptTransaction();
        return txn;
    }
}