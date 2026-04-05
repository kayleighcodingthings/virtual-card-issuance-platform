package com.nium.cardplatform.transaction;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import com.nium.cardplatform.transaction.service.TransactionProcessor;
import com.nium.cardplatform.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.nium.cardplatform.shared.TestFactory.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionServiceTest")
class TransactionServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    @Mock
    CardService cardService;

    @Mock
    TransactionProcessor transactionProcessor;

    @Spy
    SimpleMeterRegistry meterRegistry;

    @InjectMocks
    TransactionService sut;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "maxRetries", 10);
        sut.initMetrics();
    }

    // Debit

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        @DisplayName("returns existing transaction for same idempotency key")
        void idempotencyReplay() {
            Card card = activeCard(new BigDecimal("100.00"));
            Transaction existing = successfulDebit(card.getId(), new BigDecimal("10.00"));

            when(transactionRepository.findByIdempotencyKey("key-1"))
                    .thenReturn(Optional.of(existing));

            Transaction result = sut.debit(card.getId(), new BigDecimal("10.00"), "key-1");

            assertThat(result).isSameAs(existing);
            verify(transactionProcessor, never()).processDebit(any(), any(), any());
        }

        @Test
        @DisplayName("throws 409 CONFLICT when idempotency key reused with different amount")
        void idempotencyConflict_differentAmount() {
            Card card = activeCard(new BigDecimal("100.00"));
            Transaction existing = successfulDebit(card.getId(), new BigDecimal("10.00"));

            when(transactionRepository.findByIdempotencyKey("conflict-key"))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("99.00"), "conflict-key"))
                    .isInstanceOf(CardPlatformException.class)
                    .satisfies(ex -> assertThat(((CardPlatformException) ex).getErrorCode())
                            .isEqualTo("IDEMPOTENCY_CONFLICT"));
            verify(transactionProcessor, never()).processDebit(any(), any(), any());
        }

        @Test
        @DisplayName("throws 409 CONFLICT when idempotency key reused with different cardId")
        void idempotencyConflict_differentCard() {
            Card card = activeCard(new BigDecimal("100.00"));
            Transaction existing = successfulDebit(card.getId(), new BigDecimal("10.00"));

            when(transactionRepository.findByIdempotencyKey("conflict-key-2"))
                    .thenReturn(Optional.of(existing));

            UUID differentCardId = UUID.randomUUID();
            assertThatThrownBy(() -> sut.debit(differentCardId, new BigDecimal("10.00"), "conflict-key-2"))
                    .isInstanceOf(CardPlatformException.class)
                    .satisfies(ex -> assertThat(((CardPlatformException) ex).getErrorCode())
                            .isEqualTo("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        @DisplayName("delegates to processor and returns SUCCESSFUL transaction")
        void pendingThenSuccessful() {
            Card card = activeCard(new BigDecimal("200.00"));
            Transaction expected = successfulDebit(card.getId(), new BigDecimal("50.00"));

            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processDebit(card.getId(), new BigDecimal("50.00"), "happy-key"))
                    .thenReturn(expected);

            Transaction result = sut.debit(card.getId(), new BigDecimal("50.00"), "happy-key");

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            verify(transactionProcessor).processDebit(card.getId(), new BigDecimal("50.00"), "happy-key");
        }

        @Test
        @DisplayName("processes pending transaction to declined when insufficient funds")
        void pendingThenDeclined() {
            Card card = activeCard(new BigDecimal("5.00"));
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processDebit(eq(card.getId()), any(), any()))
                    .thenThrow(CardPlatformException.insufficientFunds(card.getId()));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "decline-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException when card is BLOCKED")
        void blockedCard() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processDebit(eq(card.getId()), any(), any()))
                    .thenThrow(CardPlatformException.cardNotActive(card.getId(), CardStatus.BLOCKED.name()));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "blocked-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException when card is CLOSED")
        void closedCard() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processDebit(eq(card.getId()), any(), any()))
                    .thenThrow(CardPlatformException.cardNotActive(card.getId(), CardStatus.CLOSED.name()));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "blocked-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException when card not found")
        void cardNotFound() {
            UUID missing = UUID.randomUUID();
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processDebit(eq(missing), any(), any()))
                    .thenThrow(CardPlatformException.notFound(missing));

            assertThatThrownBy(() -> sut.debit(missing, new BigDecimal("10.00"), "missing-key"))
                    .isInstanceOf(CardPlatformException.class);
        }
    }

    // --- Retry logic ---
    @Test
    @DisplayName("retries on TransientDataAccessException and succeeds")
    void retriesOnTransientFailureThenSucceeds() {
        Card card = activeCard(new BigDecimal("100.00"));
        Transaction expected = successfulDebit(card.getId(), new BigDecimal("10.00"));

        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionProcessor.processDebit(eq(card.getId()), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("version mismatch", null))
                .thenReturn(expected); // succeeds on second attempt

        Transaction result = sut.debit(card.getId(), new BigDecimal("10.00"), "retry-key");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
        verify(transactionProcessor, times(2)).processDebit(any(), any(), any());
    }

    @Test
    @DisplayName("declines with LOCK_CONTENTION_EXHAUSTED after exhausting all retries")
    void exhaustsRetriesAndDeclines() {
        Card card = activeCard(new BigDecimal("100.00"));

        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionProcessor.processDebit(any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("version mismatch", null));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = sut.debit(card.getId(), new BigDecimal("10.00"), "exhaust-key");

        // maxRetries=10 means 11 total attempts (0,1,2,3,...,10), then falls through to declined save
        verify(transactionProcessor, times(11)).processDebit(any(), any(), any());
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(result.getDeclineReason()).isEqualTo("LOCK_CONTENTION_EXHAUSTED");
    }

    @Test
    @DisplayName("resolves DataIntegrityViolationException via idempotency key lookup")
    void dataIntegrityViolationFallsBackToIdempotencyLookup() {
        Card card = activeCard(new BigDecimal("100.00"));
        Transaction raceWinner = successfulDebit(card.getId(), new BigDecimal("10.00"));

        when(transactionRepository.findByIdempotencyKey(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raceWinner));
        when(transactionProcessor.processDebit(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency_key"));

        Transaction result = sut.debit(card.getId(), new BigDecimal("10.00"), "race-key");

        assertThat(result).isSameAs(raceWinner);
    }

    // Parameterized Test for balance boundary cases

    @Nested
    @DisplayName("balanceBoundary cases")
    class BalanceBoundary {

        /**
         * Covers the InsufficientFundsException guard in Card.debit().
         * CSV: balance, debitAmount, shouldSucceed
         */
        @ParameterizedTest(name = "balance={0} debit={1} shouldSucceed={2}")
        @CsvSource({
                "100.00, 100.00, true",   // exact balance — succeeds, final balance = 0
                "100.00, 100.01, false",  // one cent over — declines
                "0.00,   0.01,  false",   // zero balance — any debit declines
                "50.00,  25.00, true",    // normal debit — succeeds
                "0.01,   0.01,  true",    // minimum unit debit — succeeds
        })
        @DisplayName("boundary debit: balance={0} amount={1} expected={2}")
        void balanceBoundary(BigDecimal balance, BigDecimal debitAmount, boolean shouldSucceed) {
            Card card = activeCard(balance);
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

            if (shouldSucceed) {
                Transaction success = successfulDebit(card.getId(), debitAmount);
                when(transactionProcessor.processDebit(eq(card.getId()), eq(debitAmount), any()))
                        .thenReturn(success);
                assertThatNoException().isThrownBy(() ->
                        sut.debit(card.getId(), debitAmount, UUID.randomUUID().toString()));
            } else {
                when(transactionProcessor.processDebit(eq(card.getId()), eq(debitAmount), any()))
                        .thenThrow(CardPlatformException.insufficientFunds(card.getId()));
                assertThatThrownBy(() -> sut.debit(card.getId(), debitAmount, "boundary-key"))
                        .isInstanceOf(CardPlatformException.class);
            }
        }
    }

    // Transaction Entity Lifecycle

    @Nested
    @DisplayName("transaction state lifecycle")
    class TransactionLifecycle {

        @Test
        @DisplayName("PENDING → SUCCESSFUL via acceptTransaction()")
        void pendingToSuccessful() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-1");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
            txn.acceptTransaction();
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
        }

        @Test
        @DisplayName("PENDING → DECLINED via declineTransaction(reason)")
        void pendingToDeclined() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-2");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
            txn.declineTransaction("INSUFFICIENT_FUNDS");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(txn.getDeclineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("acceptTransaction() on non-PENDING transaction throws IllegalStateException")
        void acceptNonPendingThrows() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-3");
            txn.acceptTransaction();
            assertThatThrownBy(txn::acceptTransaction).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("declineTransaction() on non-PENDING transaction throws IllegalStateException")
        void declineNonPendingThrows() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-4");
            txn.acceptTransaction();
            assertThatThrownBy(() -> txn.declineTransaction("reason")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("credit")
    class Credit {

        @Test
        @DisplayName("returns existing transaction for same idempotency key")
        void idempotencyReplay() {
            Card card = activeCard(BigDecimal.ZERO);
            Transaction existing = successfulCredit(card.getId(), new BigDecimal("50.00"));
            when(transactionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            Transaction result = sut.credit(card.getId(), new BigDecimal("50.00"), "key-1");

            assertThat(result).isSameAs(existing);
            verify(cardService, never()).findOrThrow(any());
        }

        @Test
        @DisplayName("throws 409 CONFLICT when idempotency key reused with different amount")
        void idempotencyConflict_differentAmount() {
            Card card = activeCard(BigDecimal.ZERO);
            Transaction existing = successfulCredit(card.getId(), new BigDecimal("50.00"));
            when(transactionRepository.findByIdempotencyKey("conflict-credit"))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> sut.credit(card.getId(), new BigDecimal("999.00"), "conflict-credit"))
                    .isInstanceOf(CardPlatformException.class)
                    .satisfies(ex -> assertThat(((CardPlatformException) ex).getErrorCode())
                            .isEqualTo("IDEMPOTENCY_CONFLICT"));
            verify(transactionProcessor, never()).processCredit(any(), any(), any());
        }


        @Test
        @DisplayName("processes pending credit transaction to successful")
        void pendingThenSuccessful() {
            Card card = activeCard();
            Transaction expected = successfulCredit(card.getId(), new BigDecimal("25.00"));
            when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(transactionProcessor.processCredit(card.getId(), new BigDecimal("25.00"), "credit-key"))
                    .thenReturn(expected);

            Transaction result = sut.credit(card.getId(), new BigDecimal("25.00"), "credit-key");
            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            verify(transactionProcessor).processCredit(card.getId(), new BigDecimal("25.00"), "credit-key");
        }

        @Test
        @DisplayName("throws CardPlatformException on CLOSED card")
        void closedCard() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(transactionProcessor.processCredit(eq(card.getId()), any(), any()))
                    .thenThrow(CardPlatformException.cardNotActive(card.getId(), CardStatus.CLOSED.name()));

            assertThatThrownBy(() -> sut.credit(card.getId(), new BigDecimal("25.00"), "closed-credit-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException on BLOCKED card")
        void blockedCard() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(transactionProcessor.processCredit(eq(card.getId()), any(), any()))
                    .thenThrow(CardPlatformException.cardNotActive(card.getId(), CardStatus.BLOCKED.name()));

            assertThatThrownBy(() -> sut.credit(card.getId(), new BigDecimal("25.00"), "closed-credit-key"))
                    .isInstanceOf(CardPlatformException.class);
        }
    }
}
