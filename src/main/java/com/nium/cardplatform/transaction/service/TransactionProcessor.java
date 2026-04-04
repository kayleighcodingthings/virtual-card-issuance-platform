package com.nium.cardplatform.transaction.service;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.events.CardAuditEvent;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionProcessor {
    private final CardService cardService;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    private Counter debitSuccessCounter;
    private Counter debitDeclinedCounter;
    private Counter creditSuccessCounter;
    private Counter creditDeclinedCounter;

    @PostConstruct
    public void initMetrics() {
        debitSuccessCounter = meterRegistry.counter("transactions.debit.success");
        debitDeclinedCounter = meterRegistry.counter("transactions.debit.declined");
        creditSuccessCounter = meterRegistry.counter("transactions.credit.success");
        creditDeclinedCounter = meterRegistry.counter("transactions.credit.declined");
    }

    @Transactional(isolation = REPEATABLE_READ)
    public Transaction processDebit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        Card card = cardService.findOrThrow(cardId);

        if (!card.isActive()) {
            // Write a DECLINED record immediately, balance hasn't been touched.
            transactionRepository.save(Transaction.declined(cardId, TransactionType.DEBIT, amount,
                    "Card was expected to be ACTIVE but was found " + card.getStatus(), idempotencyKey));
            debitDeclinedCounter.increment();
            throw CardPlatformException.cardNotActive(cardId, card.getStatus().name());
        }

        // We write a PENDING record before mutating the balance, it's an observable intermediate state.
        // If the process crashes midway between now and the successful commit, we can inspect stale PENDING records to
        // diagnose the issue.
        Transaction txn = transactionRepository.save(
                Transaction.pending(cardId, TransactionType.DEBIT, amount, idempotencyKey)
        );

        try {
            // Attempt debit, throws InsufficientFundsException if balance is insufficient
            card.debit(amount);
            cardRepository.save(card);

            // Update status to SUCCESSFUL, balance mutation and status update commit atomically
            txn.acceptTransaction();
            transactionRepository.save(txn);
            debitSuccessCounter.increment();
            eventPublisher.publishEvent(CardAuditEvent.onTransactionCompleted(cardId, txn.getId(), "DEBIT", amount));

            log.info("Debit successful: cardId={} amount={} txId={}", cardId, amount, txn.getId());
            return txn;
        } catch (Card.InsufficientFundsException e) {
            // Decline with reason
            txn.declineTransaction("INSUFFICIENT_FUNDS");
            transactionRepository.save(txn);
            debitDeclinedCounter.increment();
            log.info("Debit declined (insufficient funds): cardId={} amount={} available={}", cardId, amount, e.getAvailable());
            throw CardPlatformException.insufficientFunds(cardId);
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Transaction processCredit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        Card card = cardService.findOrThrow(cardId);
        if (!card.isActive()) {
            transactionRepository.save(Transaction.declined(cardId, TransactionType.CREDIT, amount,
                    "Card was expected to be ACTIVE but was found " + card.getStatus(), idempotencyKey));
            creditDeclinedCounter.increment();
            throw CardPlatformException.cardNotActive(cardId, card.getStatus().name());
        }

        // Write PENDING first
        Transaction txn = transactionRepository.save(
                Transaction.pending(cardId, TransactionType.CREDIT, amount, idempotencyKey)
        );

        // Credit balance then update status
        card.credit(amount);
        cardRepository.save(card);

        txn.acceptTransaction();
        transactionRepository.save(txn);
        creditSuccessCounter.increment();
        eventPublisher.publishEvent(CardAuditEvent.onTransactionCompleted(cardId, txn.getId(), "CREDIT", amount));

        log.info("Credit successful: cardId={} amount={} txId={}", cardId, amount, txn.getId());
        return txn;
    }
}
