package com.telecom.support.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of time (00-overview §9, 01 backend §6): a UTC {@link Clock} bean. Code reads time only through
 * this bean, never {@code Instant.now()} or {@code LocalDateTime.now()}, so tests can substitute a mutable fixed clock.
 */
@Configuration(proxyBeanMethods = false)
public class AppClock {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
