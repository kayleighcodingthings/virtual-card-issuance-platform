package com.nium.cardplatform.transaction.service;

import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CardService cardService;
    private final MeterRegistry meterRegistry;

    private final TransactionProcessor transactionProcessor;

    @Value("${app.transaction.optimistic-lock-max-retries:3}")
    private int maxRetries;

    private Counter debitDeclinedCounter;
    private Counter creditDeclinedCounter;

    @PostConstruct
    public void initMetrics() {
        debitDeclinedCounter = meterRegistry.counter("transactions.debit.declined");
        creditDeclinedCounter = meterRegistry.counter("transactions.credit.declined");
    }

    /**
     * Processes a debit request with idempotency and optimistic-lock retry.
     * <p>Transaction lifecycle: PENDING → SUCCESSFUL or DECLINED.
     * A PENDING record is written before the balance mutation so there is always
     * an observable record even if the process crashes mid-operation.
     * <p>Idempotency: if a transaction with the same key already exists, the cached
     * result is returned without re-processing.
     * <p>Retry strategy:
     * {@link ObjectOptimisticLockingFailureException} and
     * {@link PessimisticLockingFailureException} (SQLState 40001).
     * Both are subclasses of {@link TransientDataAccessException}
     * and are retried up to {@code maxRetries} times with exponential back-off.
     * Unlike debits, a credit exhausting all retries is declined with
     * {@code LOCK_CONTENTION_EXHAUSTED} rather than silently losing the top-up.
     * @param cardId         the card to credit
     * @param amount         the amount to add; must be positive
     * @param idempotencyKey caller-supplied key guaranteeing exactly-once processing
     * @return the resulting {@link Transaction}
     */
    @Timed(value = "transaction.debit.time", description = "Time taken to process a debit")
    public Transaction debit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> executeDebitWithRetry(cardId, amount, idempotencyKey, 0));
    }

    private Transaction executeDebitWithRetry(UUID cardId, BigDecimal amount, String idempotencyKey, int attempt) {
        try {
            return transactionProcessor.processDebit(cardId, amount, idempotencyKey);
        } catch (TransientDataAccessException e) {
            // Covers both ObjectOptimisticLockingFailureException (@Version mismatch detected
            // by Hibernate) and PessimisticLockingFailureException (SQLState 40001 conflict
            // detected by PostgreSQL at REPEATABLE_READ level). Both are transient and retryable.
            if (attempt < maxRetries) {
                log.warn("Transient failure on DEBIT, retrying... cardId={} amount={} attempt={}/{} cause={}",
                        cardId, amount, attempt + 1, maxRetries, e.getClass().getSimpleName());
                sleepBackoff(attempt);
                return executeDebitWithRetry(cardId, amount, idempotencyKey, attempt + 1);
            }
            log.error("Max retries reached ({}) for DEBIT, declining: cardId={} amount={}", maxRetries, cardId, amount);
            debitDeclinedCounter.increment();
            return transactionRepository.save(Transaction.declined(cardId, TransactionType.DEBIT, amount,
                    "LOCK_CONTENTION_EXHAUSTED", idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Race on the unique idempotency_key constraint — another thread committed the
            // same idempotency key between our initial check and our insert. Fetch and return
            // the already-committed transaction instead of failing.
            log.debug("Data integrity violation on DEBIT, likely due to concurrent transaction with same idempotency key: cardId={} amount={} idempotencyKey={}", cardId, amount, idempotencyKey);
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> CardPlatformException.internalError("Idempotency race resolution failed"));
        }
    }

    /**
     * Processes a credit (top-up) request with idempotency and optimistic-lock retry.
     * <p>Transaction lifecycle: PENDING → SUCCESSFUL or DECLINED.
     * A PENDING record is written before the balance mutation so there is always
     * an observable record even if the process crashes mid-operation.
     * <p>Idempotency: if a transaction with the same key already exists, the cached
     * result is returned without re-processing.
     * <p>Retry strategy: the same transient failure types as debit can occur —
     * {@link ObjectOptimisticLockingFailureException} and
     * {@link PessimisticLockingFailureException} (SQLState 40001).
     * Both are subclasses of {@link TransientDataAccessException}
     * and are retried up to {@code maxRetries} times with exponential back-off.
     * Unlike debits, a credit exhausting all retries is declined with
     * {@code LOCK_CONTENTION_EXHAUSTED} rather than silently losing the top-up.
     * @param cardId         the card to credit
     * @param amount         the amount to add; must be positive
     * @param idempotencyKey caller-supplied key guaranteeing exactly-once processing
     * @return the resulting {@link Transaction}
     */
    public Transaction credit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> executeCreditWithRetry(cardId, amount, idempotencyKey, 0));
    }

    private Transaction executeCreditWithRetry(UUID cardId, BigDecimal amount, String idempotencyKey, int attempt) {
        try {
            return transactionProcessor.processCredit(cardId, amount, idempotencyKey);
        } catch (TransientDataAccessException e) {
            if (attempt < maxRetries) {
                log.warn("Transient failure on CREDIT, retrying... cardId={} amount={} attempt={}/{} cause={}",
                        cardId, amount, attempt + 1, maxRetries, e.getClass().getSimpleName());
                sleepBackoff(attempt);
                return executeCreditWithRetry(cardId, amount, idempotencyKey, attempt + 1);
            }
            log.error("Max retries reached ({}) for CREDIT, declining: cardId={} amount={}", maxRetries, cardId, amount);
            creditDeclinedCounter.increment();
            return transactionRepository.save(Transaction.declined(cardId, TransactionType.CREDIT, amount,
                    "LOCK_CONTENTION_EXHAUSTED", idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            log.debug("Data integrity violation on CREDIT, likely due to concurrent transaction with same idempotency key: cardId={} amount={} idempotencyKey={}", cardId, amount, idempotencyKey);
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> CardPlatformException.internalError("Idempotency race resolution failed"));
        }
    }

    /**
     * Returns a paginated, descending-chronological list of transactions for a card.
     * <p>Verifies the card exists before querying — throws
     * {@link CardPlatformException} (HTTP 404)
     * if the card ID is unknown, preventing silent empty-page responses for bad IDs.
     *
     * @param cardId   the card whose transaction history to retrieve
     * @param pageable pagination and sorting parameters
     * @return a page of transactions ordered by {@code created_at DESC}
     * @throws CardPlatformException if the card is not found
     */
    @Transactional(readOnly = true)
    public Page<Transaction> getTransactions(UUID cardId, Pageable pageable) {
        cardService.findOrThrow(cardId);
        return transactionRepository.findByCardIdOrderByCreatedAtDesc(cardId, pageable);
    }

    private void sleepBackoff(int attempt) {
        try {
            long jitter = (long) (Math.random() * 50);
            Thread.sleep(50L * (attempt + 1) + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
