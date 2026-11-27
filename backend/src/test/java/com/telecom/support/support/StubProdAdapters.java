package com.telecom.support.support;

import com.telecom.support.notification.NotificationSender;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Non-mock stand-ins for the outbound ports, for tests that start the application under {@code prod} (03-inbound
 * backend §6, §8): prod has no mock notification sender ({@code ProfileGuard} refuses it) and no real adapter exists
 * yet. Active only with {@code prod} and {@code test.stub-adapters=true}, so an accidentally scanned copy changes
 * nothing elsewhere. They deliver nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(name = "test.stub-adapters", havingValue = "true")
public class StubProdAdapters {

    @Bean
    @Primary
    NotificationSender stubNotificationSender() {
        return new StubNotificationSender();
    }

    static final class StubNotificationSender implements NotificationSender {
        @Override
        public void send(Channel channel, String destination, Template template, Map<String, String> params) {
            // delivers nothing
        }
    }
}
