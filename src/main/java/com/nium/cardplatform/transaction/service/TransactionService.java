package com.nium.cardplatform.transaction.service;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CardService cardService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${app.transaction.optimistic-lock-max-retries:3}")
    private int maxRetries;

    private Counter debitSuccessCounter;
    private Counter debitDeclinedCounter;
    private Counter creditSuccessCounter;

    @PostConstruct
    public void initMetrics() {
        debitSuccessCounter = meterRegistry.counter("transactions.debit.success");
        debitDeclinedCounter = meterRegistry.counter("transactions.debit.declined");
        creditSuccessCounter = meterRegistry.counter("transactions.credit.success");
    }

    /**
     * Processes a debit request with idempotency and optimistic-lock retry.
     * <p>Transaction lifecycle: PENDING -> SUCCESSFUL or DECLINED.
     * A PENDING record is written before the balance mutation so there is always
     * an observable record even if the process crashes mid-operation.
     * <p>Idempotency: if a transaction with the same key already exists, the cached
     * result is returned without re-processing.
     * <p>Optimistic-lock retry: JPA @Version causes ObjectOptimisticLockingFailureException
     * when two concurrent transactions race on the same card. We retry up to maxRetries
     * times with a short back-off. After exhausting retries a DECLINED record is returned.
     */
    public Transaction debit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByItempotencyKey(idempotencyKey)
                .orElseGet(() -> executeDebitWithRetry(cardId, amount, idempotencyKey, 0));
    }

    private Transaction executeDebitWithRetry(UUID cardId, BigDecimal amount, String idempotencyKey, int attempt) {
        try {
            return processDebit(cardId, amount, idempotencyKey);
        } catch (ObjectOptimisticLockingFailureException e) {
            if (attempt < maxRetries) {
                log.warn("Optimistic lock failure on debit, retrying... cardId={} amount={} attempt={}/{}", cardId, amount, attempt + 1, maxRetries);
                sleepBackoff(attempt);
                return executeDebitWithRetry(cardId, amount, idempotencyKey, attempt + 1);
            }
            log.error("Max retries reached ({}) for debit transaction, failing: cardId={} amount={}", maxRetries, cardId, amount);
            // TODO: incremement declined metric

            return transactionRepository.save(Transaction.declined(cardId, TransactionType.DEBIT, amount,
                    "LOCK_CONTENTION_EXHAUSTED", idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Race on unique idempotency_key constraint - another thread committed already
            log.debug("Data integrity violation on debit, likely due to concurrent transaction with same idempotency key: cardId={} amount={} idempotencyKey={}", cardId, amount, idempotencyKey);
            return transactionRepository.findByItempotencyKey(idempotencyKey)
                    .orElseThrow(() -> CardPlatformException.internalError("Idempotency race resolution failed"));
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    protected Transaction processDebit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        Card card = cardService.findOrThrow(cardId);

        if (!card.isActive()) {
            // Write a DECLINED record immediately, balance hasn't been touched.
            transactionRepository.save(Transaction.declined(cardId, TransactionType.DEBIT, amount,
                    "Card was expected to be ACTIVE but was found " + card.getStatus(), idempotencyKey));

            // TODO: Increment declined metric
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

            // Update status to SUCCESSFUL, balance mutation and status update commit atomically
            txn.acceptTransaction();
            transactionRepository.save(txn);
            // TODO: increment success counter
            // TODO: publish event

            log.info("Debit successful: cardId={} amount={} txId={}", cardId, amount, txn.getId());
            return txn;
        } catch (Card.InsufficientFundsException e) {
            // Decline with reason
            txn.declineTransaction("INSUFFICIENT_FUNDS");
            transactionRepository.save(txn);
            // TODO: increment decline metric
            log.info("Debit declined (insufficient funds): cardId={} amount={} available={}", cardId, amount, e.getAvailable());
            throw CardPlatformException.insufficientFunds(cardId);
        }
    }

    /**
     * Credit also uses the PENDING -> SUCCESSFUL lifecycle for consistency.
     * Credits cannot produce an illegal balance state so no retry is needed, but we still want the observability
     * of a PENDING state and the idempotency guarantee.
     *
     */
    public Transaction credit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByItempotencyKey(idempotencyKey)
                .orElseGet(() -> executeCredit(cardId, amount, idempotencyKey));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    protected Transaction executeCredit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        Card card = cardService.findOrThrow(cardId);
        if (!card.isActive()) {
            transactionRepository.save(Transaction.declined(cardId, TransactionType.CREDIT, amount,
                    "Card was expected to be ACTIVE but was found " + card.getStatus(), idempotencyKey));
            throw CardPlatformException.cardNotActive(cardId, card.getStatus().name());
        }

        // Write PENDING first
        Transaction txn = transactionRepository.save(
                Transaction.pending(cardId, TransactionType.CREDIT, amount, idempotencyKey)
        );

        // Credit balance then update status
        card.credit(amount);
        txn.acceptTransaction();
        transactionRepository.save(txn);
        // TODO: increment credit success counter
        // TODO: publish event

        log.info("Credit successful: cardId={} amount={} txId={}", cardId, amount, txn.getId());
        return txn;
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getTransactions(UUID cardId, Pageable pageable) {
        cardService.findOrThrow(cardId);
        return transactionRepository.findByCardIdOrderByCreatedAtDesc(cardId, pageable);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(50L * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
