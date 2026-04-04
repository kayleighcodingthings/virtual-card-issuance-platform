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

    /**
     * Executes a single debit attempt within a REPEATABLE_READ transaction.
     * <p>This method is the transactional unit of work called by {@link TransactionService#debit} from outside the Spring proxy,
     * ensuring the {@code @Transactional} boundary is honoured on every retry attempt.
     * <p>Lifecycle within this transaction:
     * <ol>
     *   <li>Verify the card is ACTIVE — write DECLINED and throw if not.</li>
     *   <li>Write a PENDING record for observability before any balance mutation.</li>
     *   <li>Call {@link Card#debit} — throws {@link Card.InsufficientFundsException} if the balance would go negative.</li>
     *   <li>On success: persist the updated card balance and mark the transaction SUCCESSFUL.</li>
     *   <li>On insufficient funds: mark the transaction DECLINED and rethrow as {@link CardPlatformException}.</li>
     * </ol>
     * <p>REPEATABLE_READ ensures that concurrent transactions racing on the same card
     * will trigger a version conflict (caught by the caller's retry loop) rather than
     * silently producing a negative balance.
     * @param cardId         the card to debit
     * @param amount         the amount to deduct; must be positive
     * @param idempotencyKey the idempotency key for the transaction record
     * @return the resulting {@link Transaction}
     * @throws CardPlatformException on card not found, card not active, or insufficient funds
     */
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
     * Executes a single credit attempt within a REPEATABLE_READ transaction.
     * <p>Mirrors the structure of {@link #processDebit} but with no lower-bound balance constraint — a credit can never
     * produce an illegal balance state. REPEATABLE_READ is still required to prevent lost-update anomalies under
     * concurrent credits: without it, two threads could both read balance=100, both compute 100+10=110, and both commit,
     * silently losing one credit.
     * <p>Lifecycle within this transaction:
     * <ol>
     *   <li>Verify the card is ACTIVE — write DECLINED and throw if not.</li>
     *   <li>Write a PENDING record before the balance mutation.</li>
     *   <li>Apply the credit and save the updated card.</li>
     *   <li>Mark the transaction SUCCESSFUL and publish an audit event.</li>
     * </ol>
     * @param cardId         the card to credit
     * @param amount         the amount to add; must be positive
     * @param idempotencyKey the idempotency key for the transaction record
     * @return the resulting {@link Transaction}
     * @throws com.nium.cardplatform.shared.exception.CardPlatformException on card not found
     *         or card not active
     */
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
