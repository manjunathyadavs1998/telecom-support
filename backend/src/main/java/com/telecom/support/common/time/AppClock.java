package com.telecom.support.common.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single injectable UTC clock (testable time) and the documented display zone (XC-05: Asia/Kolkata). */
@Configuration
public class AppClock {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ZoneId displayZone(@Value("${app.display-time-zone:Asia/Kolkata}") String zone) {
        return ZoneId.of(zone);
    }
}
