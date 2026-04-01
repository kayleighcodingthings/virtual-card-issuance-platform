package com.nium.cardplatform.card.scheduler;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.service.CardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@DisallowConcurrentExecution
@RequiredArgsConstructor
public class CardExpiryJob implements Job {

    private final CardRepository cardRepository;
    private final CardService cardService;

    @Override
    public void execute(JobExecutionContext context) {
        LocalDateTime now = LocalDateTime.now();
        List<Card> expiredCards = cardRepository.findExpiredActiveCards(now, CardStatus.ACTIVE);

        if (expiredCards.isEmpty()) {
            log.debug("No cards to expire at {}", now);
        }

        log.info("Found {} expired card(s) to process", expiredCards.size());
        int success = 0, failed = 0;

        for (Card card : expiredCards) {
            try {
                cardService.expireCard(card);
                success++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to expire cardId={} reason={}", card.getId(), e.getMessage(), e);
            }
        }

        log.info("Expiry job completed: expired={} failed={}", success, failed);
    }
}
