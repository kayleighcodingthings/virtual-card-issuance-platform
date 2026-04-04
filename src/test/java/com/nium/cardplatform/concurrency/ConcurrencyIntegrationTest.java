package com.nium.cardplatform.concurrency;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.integration.BaseIntegrationTest;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import com.nium.cardplatform.transaction.service.TransactionService;
import org.checkerframework.checker.units.qual.C;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Concurrency safety test")
class ConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    TransactionService transactionService;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent debits should never cause negative balance")
    void concurrentDebit_balanceNeverNegative() throws InterruptedException {
        Card card = persistCardToDB(new BigDecimal("500.00"));
        int threadCount = 50;
        BigDecimal debitAmount = new BigDecimal("20.00");
        // 500 / 20 = 25 debits can succeed at most

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.debit(card.getId(), debitAmount, "concurrency-test-" + Thread.currentThread().getId());
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    failedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Card result = cardRepository.findById(card.getId()).orElseThrow();

        // invariant 1 — balance never went negative
        assertThat(result.getBalance())
                .as("Balance must never go below zero")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // invariant 2 — balance is exactly zero (all valid spends consumed)
        assertThat(result.getBalance())
                .as("Final balance should be exactly 0.00 — all 25 allowed spends consumed")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // invariant 3 — application layer counted 25 successes
        assertThat(successCount.get())
                .as("Exactly 25 of 50 spend attempts should succeed")
                .isEqualTo(25);

        long dbSuccessful = transactionRepository.countByCardIdAndStatus(card.getId(), TransactionStatus.SUCCESSFUL);
        // invariant 4 — database also recorded exactly 25 SUCCESSFUL transactions
        assertThat(dbSuccessful)
                .as("DB count of SUCCESSFUL transactions must match succeeded counter")
                .isEqualTo(25);
    }

    @Test
    @DisplayName("Concurrent credits should never cause lost updates — final balance should reflect all credits")
    void concurrentCredit_noLostUpdates() throws InterruptedException {
        Card card = persistCardToDB(BigDecimal.ZERO);
        int threadCount = 50;
        BigDecimal creditAmount = new BigDecimal("10.00");
        // 50 credits of 10.00 should result in final balance of 500.

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.credit(card.getId(), creditAmount, "concurrency-test-" + Thread.currentThread().getId());
                } catch (InterruptedException e) {
                    // Ignored for this test
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Card result = cardRepository.findById(card.getId()).orElseThrow();

        assertThat(result.getBalance())
                .as("Final balance should reflect all 50 credits of 10.00, resulting in 500.00")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    private Card persistCardToDB(BigDecimal balance) {
        return cardRepository.save(Card.builder()
                .cardholderName("Concurrency Test")
                .balance(balance)
                .status(CardStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusYears(3))
                .build()
        );
    }

}
