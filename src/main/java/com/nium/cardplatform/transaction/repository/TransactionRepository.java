package com.nium.cardplatform.transaction.repository;

import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Page<Transaction> findByCardIdOrderByCreatedAtDesc(UUID cardId, Pageable pageable);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** Used to verify the exact number of SUCCESSFUL transactions in concurrency tests. */
    long countByCardIdAndStatus(UUID cardId, TransactionStatus status);

}