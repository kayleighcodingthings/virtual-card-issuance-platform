package com.nium.cardplatform.card.repository;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CardRepository extends JpaRepository<Card, UUID> {

    /**
     * Returns only ACTIVE cards whose expiry date has passed.
     * <p>The status = ACTIVE filter is critical. Without it the scheduler would
     * re-process already-expired or closed cards on every run, producing
     * spurious state changes and audit events.
     * <p>This query uses the partial index idx_card_active_expires for efficiency.
     */
    @Query("SELECT c FROM Card c WHERE c.expiresAt <= :now AND c.status = :status")
    List<Card> findExpiredActiveCards(@Param("now") LocalDateTime now,
                                      @Param("status") CardStatus status);
}