package com.telecom.support.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.telecom.support.SupportApplication;
import com.telecom.support.payment.PaymentGateway.CheckoutSession;
import com.telecom.support.payment.PaymentGateway.EventType;
import com.telecom.support.payment.PaymentGateway.GatewayEvent;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.Method;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The dev/test-only mock checkout page and its prod guard (design 02-customer backend §4, §6, §8, §9; CUS-08.2, NFR-SEC).
 * The page is public (no permission, no session) and CSRF-exempt by design, so it has no 401/403 cases; its protection is
 * the random session id. Its signed webhook goes to a local capture server standing in for E18 (built in B18), and is
 * verified with the gateway's own {@link MockPaymentGateway#verifyWebhook}. Both of its contexts (mock on, mock off)
 * are closed after use so they do not hold MySQL connections in the shared context cache.
 */
@DirtiesContext
@TestPropertySource(properties = "app.payment.mock.enabled=true")
class MockCheckoutIT extends AbstractMySqlIT {

    private static final String RETURN_URL = "http://localhost:5173/payments/return";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String XSRF = "mock-checkout-xsrf-token";
    private static final BlockingQueue<Captured> WEBHOOKS = new LinkedBlockingQueue<>();
    private static final HttpServer CAPTURE = startCapture();

    record Captured(byte[] body, HttpHeaders headers) {}

    @DynamicPropertySource
    static void webhookUrl(DynamicPropertyRegistry registry) {
        registry.add("app.payment.mock.webhook-url",
                () -> "http://localhost:" + CAPTURE.getAddress().getPort() + "/webhooks");
    }

    @AfterAll
    static void stopCapture() {
        CAPTURE.stop(0);
    }

    @Autowired
    MockPaymentGateway gateway;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${app.payment.return-url-base}")
    String returnUrlBase;

    @BeforeEach
    void clearWebhooks() {
        WEBHOOKS.clear();
    }

    @Test
    void pageShowsPendingSessionWithChoicesAndNoCardFields() throws Exception {
        CheckoutSession session = open("PAY-TM-PAGE");

        mvc.perform(get(session.redirectUrl()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("PAY-TM-PAGE"),
                        org.hamcrest.Matchers.containsString("INR 399.00"),
                        org.hamcrest.Matchers.containsString("value=\"PAY\""),
                        org.hamcrest.Matchers.containsString("value=\"FAIL\""),
                        org.hamcrest.Matchers.containsString("value=\"LEAVE_PENDING\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cvv")))));
    }

    @Test
    void payPostsCorrectlySignedSuccessWebhookAndRedirects() throws Exception {
        CheckoutSession session = open("PAY-TM-OK");

        // no CSRF token and no session: the page is CSRF-exempt and public (§4)
        mvc.perform(post(session.redirectUrl()).param("outcome", "PAY").param("method", "CARD"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, RETURN_URL));

        Captured captured = WEBHOOKS.poll(10, TimeUnit.SECONDS);
        assertThat(captured).as("webhook delivered").isNotNull();
        assertThat(captured.headers().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
        GatewayEvent event = gateway.verifyWebhook(captured.headers(), captured.body());
        assertThat(event.type()).isEqualTo(EventType.SUCCEEDED);
        assertThat(event.paymentReference()).isEqualTo("PAY-TM-OK");
        assertThat(event.sessionId()).isEqualTo(session.sessionId());
        assertThat(event.method()).isEqualTo(Method.CARD);
        assertThat(event.gatewayTransactionId()).isNotBlank();
        assertThat(event.amount()).isEqualByComparingTo("399.00");
        assertThat(gateway.getStatus("PAY-TM-OK").status()).isEqualTo(GatewayStatus.SUCCEEDED);
    }

    @Test
    void failPostsSignedFailedWebhookAndRedirects() throws Exception {
        CheckoutSession session = open("PAY-TM-FAIL");

        mvc.perform(post(session.redirectUrl()).param("outcome", "FAIL"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, RETURN_URL));

        Captured captured = WEBHOOKS.poll(10, TimeUnit.SECONDS);
        assertThat(captured).isNotNull();
        GatewayEvent event = gateway.verifyWebhook(captured.headers(), captured.body());
        assertThat(event.type()).isEqualTo(EventType.FAILED);
        assertThat(event.paymentReference()).isEqualTo("PAY-TM-FAIL");
        assertThat(gateway.getStatus("PAY-TM-FAIL").status()).isEqualTo(GatewayStatus.FAILED);
    }

    @Test
    void leavePendingRedirectsWithoutWebhook() throws Exception {
        CheckoutSession session = open("PAY-TM-LEAVE");

        mvc.perform(post(session.redirectUrl()).param("outcome", "LEAVE_PENDING"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, RETURN_URL));

        assertThat(WEBHOOKS.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(gateway.getStatus("PAY-TM-LEAVE").status()).isEqualTo(GatewayStatus.PENDING);
    }

    @Test
    void settledSessionCannotBeSettledAgain() throws Exception {
        CheckoutSession session = open("PAY-TM-TWICE");
        mvc.perform(post(session.redirectUrl()).param("outcome", "FAIL")).andExpect(status().isSeeOther());
        assertThat(WEBHOOKS.poll(10, TimeUnit.SECONDS)).isNotNull();

        mvc.perform(post(session.redirectUrl()).param("outcome", "PAY").param("method", "UPI"))
                .andExpect(status().isConflict());
        mvc.perform(get(session.redirectUrl())).andExpect(status().isConflict());

        assertThat(WEBHOOKS.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(gateway.getStatus("PAY-TM-TWICE").status()).isEqualTo(GatewayStatus.FAILED);
    }

    @Test
    void unknownOrMalformedSessionIs404() throws Exception {
        mvc.perform(get("/mock-gateway/checkout/0123456789abcdef0123456789abcdef")).andExpect(status().isNotFound());
        mvc.perform(get("/mock-gateway/checkout/not-a-session")).andExpect(status().isNotFound());
        mvc.perform(post("/mock-gateway/checkout/0123456789abcdef0123456789abcdef").param("outcome", "FAIL"))
                .andExpect(status().isNotFound());
        assertThat(WEBHOOKS.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void invalidChoiceIs400AndLeavesSessionPending() throws Exception {
        CheckoutSession session = open("PAY-TM-BAD");

        mvc.perform(post(session.redirectUrl()).param("outcome", "REFUND")).andExpect(status().isBadRequest());
        mvc.perform(post(session.redirectUrl()).param("outcome", "PAY").param("method", "WALLET"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(session.redirectUrl()).param("outcome", "PAY")).andExpect(status().isBadRequest());

        assertThat(gateway.getStatus("PAY-TM-BAD").status()).isEqualTo(GatewayStatus.PENDING);
    }

    @Test
    void e15CheckoutReturnsTheBrowserToTheCustomerPaymentPage() throws Exception {
        // erratum E4: the page sends the browser to {app.payment.return-url-base}/customer/payments/{reference}
        String invoiceRef = "INV-T-M" + String.format("%05d", SEQ.incrementAndGet());
        jdbc.update("""
                INSERT INTO invoices (account_id, invoice_ref, period_start, period_end, issue_date, due_date,
                                      amount_due, amount_paid, status, created_at, updated_at)
                SELECT a.id, :ref, CURRENT_DATE - INTERVAL 30 DAY, CURRENT_DATE - INTERVAL 1 DAY, CURRENT_DATE,
                       CURRENT_DATE + INTERVAL 15 DAY, 399.00, 0.00, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM accounts a WHERE a.account_ref = 'ACC-T-0001'
                """, Map.of("ref", invoiceRef));
        try {
            long invoiceId = jdbc.queryForObject("SELECT id FROM invoices WHERE invoice_ref = :ref",
                    Map.of("ref", invoiceRef), Long.class);
            MvcResult created = mvc.perform(post("/api/v1/customers/me/payments")
                            .cookie(login("+919900000001"), new Cookie("XSRF-TOKEN", XSRF))
                            .header("X-XSRF-TOKEN", XSRF)
                            .header("Idempotency-Key", "k-mock-return-" + System.nanoTime())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("invoiceId", invoiceId, "expectedAmount", "399.00"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Map<String, Object> body = objectMapper.readValue(created.getResponse().getContentAsString(),
                    new TypeReference<Map<String, Object>>() { });
            String reference = (String) body.get("reference");

            mvc.perform(post((String) body.get("redirectUrl")).param("outcome", "LEAVE_PENDING"))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string(HttpHeaders.LOCATION,
                            returnUrlBase + "/customer/payments/" + reference));
        } finally {
            jdbc.update("""
                    DELETE p FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE i.invoice_ref = :ref
                    """, Map.of("ref", invoiceRef));
            jdbc.update("DELETE FROM invoices WHERE invoice_ref = :ref", Map.of("ref", invoiceRef));
        }
    }

    @Test
    void prodWithMockEnabledFailsToStart() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(SupportApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");

        assertThatThrownBy(() -> app.run(
                "--DB_URL=" + MYSQL.getJdbcUrl(),
                "--DB_USERNAME=" + MYSQL.getUsername(),
                "--DB_PASSWORD=" + MYSQL.getPassword(),
                "--spring.flyway.locations=classpath:db/migration,classpath:db/test-data",
                "--server.port=0",
                "--app.payment.mock.enabled=true"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'prod'")
                .hasMessageContaining("app.payment.mock.enabled");
    }

    /** Mock disabled (the default outside dev/test): nothing serves the page, and the SPA does not catch it. */
    @Nested
    @DirtiesContext
    @TestPropertySource(properties = "app.payment.mock.enabled=false")
    class Disabled {

        @Autowired
        MockMvc disabledMvc;

        @Autowired
        MockPaymentGateway disabledGateway;

        @Test
        void checkoutPathsAre404() throws Exception {
            String ref = "PAY-TM-OFF-" + SEQ.incrementAndGet();
            CheckoutSession session = disabledGateway.createCheckout(ref, new BigDecimal("399.00"), "INR", RETURN_URL);

            disabledMvc.perform(get(session.redirectUrl())).andExpect(status().isNotFound());
            disabledMvc.perform(post(session.redirectUrl()).with(csrf()).param("outcome", "FAIL"))
                    .andExpect(status().isNotFound());
            // and no longer CSRF-exempt
            disabledMvc.perform(post(session.redirectUrl()).param("outcome", "FAIL"))
                    .andExpect(status().isForbidden());
            assertThat(disabledGateway.getStatus(ref).status()).isEqualTo(GatewayStatus.PENDING);
        }
    }

    // ------------------------------------------------------------------ helpers

    private CheckoutSession open(String prefix) {
        return gateway.createCheckout(prefix, new BigDecimal("399.00"), "INR", RETURN_URL);
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = SEQ.incrementAndGet();
        String ip = "10.29." + (n / 250) + "." + (n % 250 + 1);
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        for (Cookie c : result.getResponse().getCookies()) {
            if (c.getName().equals("SESSION")) {
                return new Cookie("SESSION", c.getValue());
            }
        }
        throw new AssertionError("no session cookie");
    }

    private static HttpServer startCapture() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/webhooks", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HttpHeaders headers = new HttpHeaders();
                exchange.getRequestHeaders().forEach(headers::addAll);
                WEBHOOKS.add(new Captured(body, headers));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
