package com.nium.cardplatform.card.scheduler;

import org.quartz.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class QuartzConfig {

    @Value("${app.card.expiry-job-interval:PT15M}")
    private Duration expiryJobInterval;

    @Bean
    public JobDetail cardExpiryJobDetail() {
        return JobBuilder.newJob(CardExpiryJob.class)
                .withIdentity("cardExpiryJob", "card")
                .withDescription("Expires ACTIVE cards past their expiresAt date")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger cardExpiryTrigger(JobDetail cardExpiryJobDetail) {
        // Runs every minute; fixedDelay-style via Quartz SimpleScheduleBuilder
        return TriggerBuilder.newTrigger()
                .forJob(cardExpiryJobDetail)
                .withIdentity("cardExpiryTrigger", "card")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(expiryJobInterval.toMinutesPart())
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithExistingCount())
                .build();
    }
}
