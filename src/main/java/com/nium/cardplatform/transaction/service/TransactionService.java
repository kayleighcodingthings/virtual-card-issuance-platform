package com.nium.cardplatform.transaction.service;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.events.CardAuditEvent;
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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableAspectJAutoProxy(exposeProxy = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
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
     * <p>Processes a debit request with idempotency and optimistic-lock retry.
     * <p>Transaction lifecycle: PENDING -> SUCCESSFUL or DECLINED.
     * A PENDING record is written before the balance mutation so there is always
     * an observable record even if the process crashes mid-operation.
     * <p>Idempotency: if a transaction with the same key already exists, the cached
     * result is returned without re-processing.
     * <p>Retry strategy: two distinct transient failures can occur under concurrency:
     * - {@link ObjectOptimisticLockingFailureException} — Hibernate detected a @Version
     * mismatch (UPDATE affected 0 rows), meaning another transaction committed first.
     * - {@link PessimisticLockingFailureException} — PostgreSQL itself aborted the transaction
     * with SQLState 40001 ("could not serialize access due to concurrent update")
     * at the REPEATABLE_READ level, before Hibernate could check the version.
     * Despite the name, this is caused by snapshot isolation, not explicit locks.
     * <p>Both are subclasses of {@link TransientDataAccessException} and are safe to retry.
     * The retry loop lives outside the @{@link Transactional} boundary so each attempt starts
     * a completely fresh transaction. AopContext.currentProxy() is used to call
     * processDebit() through Spring's proxy so the @{@link Transactional} annotation is honoured —
     * a direct this.processDebit() call would bypass the proxy and run without a transaction.
     */
    @Timed(value = "transaction.debit.time", description = "Time taken to process a debit")
    public Transaction debit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> executeDebitWithRetry(cardId, amount, idempotencyKey, 0));
    }

    private Transaction executeDebitWithRetry(UUID cardId, BigDecimal amount, String idempotencyKey, int attempt) {
        try {
            return ((TransactionService) AopContext.currentProxy()).processDebit(cardId, amount, idempotencyKey);
        } catch (TransientDataAccessException e) {
            // Covers both ObjectOptimisticLockingFailureException (@Version mismatch detected
            // by Hibernate) and PessimisticLockingFailureException (SQLState 40001 conflict
            // detected by PostgreSQL at REPEATABLE_READ level). Both are transient and retryable.
            if (attempt < maxRetries) {
                log.warn("Transient failure on debit, retrying... cardId={} amount={} attempt={}/{} cause={}",
                        cardId, amount, attempt + 1, maxRetries, e.getClass().getSimpleName());
                sleepBackoff(attempt);
                return executeDebitWithRetry(cardId, amount, idempotencyKey, attempt + 1);
            }
            log.error("Max retries reached ({}) for debit, declining: cardId={} amount={}", maxRetries, cardId, amount);
            debitDeclinedCounter.increment();
            return transactionRepository.save(Transaction.declined(cardId, TransactionType.DEBIT, amount,
                    "LOCK_CONTENTION_EXHAUSTED", idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Race on the unique idempotency_key constraint — another thread committed the
            // same idempotency key between our initial check and our insert. Fetch and return
            // the already-committed transaction instead of failing.
            log.debug("Data integrity violation on debit, likely due to concurrent transaction with same idempotency key: cardId={} amount={} idempotencyKey={}", cardId, amount, idempotencyKey);
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> CardPlatformException.internalError("Idempotency race resolution failed"));
        }
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

    /**
     * Credit also uses the PENDING -> SUCCESSFUL lifecycle for consistency.
     * Credits cannot produce an illegal balance state so no retry is needed, but we still want the observability
     * of a PENDING state and the idempotency guarantee.
     *
     */
    public Transaction credit(UUID cardId, BigDecimal amount, String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
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
        cardRepository.save(card);

        txn.acceptTransaction();
        transactionRepository.save(txn);
        creditSuccessCounter.increment();
        eventPublisher.publishEvent(CardAuditEvent.onTransactionCompleted(cardId, txn.getId(), "CREDIT", amount));

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
