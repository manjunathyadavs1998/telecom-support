package com.telecom.support.payment;

import com.telecom.support.payment.MockPaymentGateway.Session;
import com.telecom.support.payment.MockPaymentGateway.SignedWebhook;
import com.telecom.support.payment.PaymentGateway.GatewayEvent;
import com.telecom.support.payment.PaymentGateway.GatewayStatus;
import com.telecom.support.payment.PaymentGateway.Method;
import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The mock hosted checkout page (design 02-customer backend §4, §6, §8; CUS-08.2), dev/test only.
 *
 * <p>Registered only when {@code app.payment.mock.enabled=true}; {@code ProfileGuard} refuses that with {@code prod}.
 * The paths are public and CSRF-exempt ({@code SecurityConfig}); the only protection is the 128-bit random session id
 * (accepted for a dev-only mock). {@code GET} shows the session and the choices Pay (UPI/CARD), Fail and Leave pending.
 * {@code POST} settles the session through {@link MockPaymentGateway#complete}, posts the signed webhook to
 * {@code app.payment.mock.webhook-url} (Pay and Fail only) and answers 303 to the session's {@code returnUrl}. A webhook
 * that cannot be delivered is logged and left to the reconciliation job (§3.1: lost webhook); the page never changes a
 * payment row itself. No card data is asked for or accepted.
 */
@RestController
@ConditionalOnProperty(name = "app.payment.mock.enabled", havingValue = "true")
class MockCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(MockCheckoutController.class);
    private static final Pattern SESSION_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    /** The tester's choice on the page. */
    enum Outcome { PAY, FAIL, LEAVE_PENDING }

    private final MockPaymentGateway gateway;
    private final URI webhookUrl;
    private final Duration timeout;
    private final HttpClient http;

    MockCheckoutController(MockPaymentGateway gateway, PaymentGatewayProperties properties) {
        this.gateway = gateway;
        String url = properties.mock().webhookUrl();
        this.webhookUrl = url == null ? null : URI.create(url);
        this.timeout = properties.gateway().timeout();
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GetMapping(MockPaymentGateway.CHECKOUT_PATH + "{sessionId}")
    @PreAuthorize("permitAll()")
    ResponseEntity<String> page(@PathVariable String sessionId) {
        Optional<Session> found = session(sessionId);
        if (found.isEmpty()) {
            return html(HttpStatus.NOT_FOUND, "Checkout not found", "<p>This checkout session does not exist.</p>");
        }
        Session session = found.get();
        if (session.status() != GatewayStatus.PENDING) {
            return finished(session);
        }
        String action = HtmlUtils.htmlEscape(MockPaymentGateway.CHECKOUT_PATH + session.sessionId());
        String body = """
                <p>Merchant reference <strong>%s</strong></p>
                <p>Amount <strong>%s %s</strong></p>
                <form method="post" action="%s">
                  <fieldset>
                    <legend>Pay</legend>
                    <label><input type="radio" name="method" value="UPI" checked> UPI</label>
                    <label><input type="radio" name="method" value="CARD"> Card</label>
                    <button type="submit" name="outcome" value="PAY">Pay</button>
                  </fieldset>
                  <button type="submit" name="outcome" value="FAIL">Fail</button>
                  <button type="submit" name="outcome" value="LEAVE_PENDING">Leave pending</button>
                </form>
                <p><small>Mock gateway for development and tests. No card details are collected.</small></p>
                """.formatted(HtmlUtils.htmlEscape(session.paymentRef()),
                HtmlUtils.htmlEscape(session.currency()),
                HtmlUtils.htmlEscape(session.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()),
                action);
        return html(HttpStatus.OK, "Mock checkout", body);
    }

    @PostMapping(MockPaymentGateway.CHECKOUT_PATH + "{sessionId}")
    @PreAuthorize("permitAll()")
    ResponseEntity<String> submit(@PathVariable String sessionId,
                                  @RequestParam(name = "outcome", required = false) String outcome,
                                  @RequestParam(name = "method", required = false) String method) {
        Optional<Session> found = session(sessionId);
        if (found.isEmpty()) {
            return html(HttpStatus.NOT_FOUND, "Checkout not found", "<p>This checkout session does not exist.</p>");
        }
        Session session = found.get();
        Outcome choice = parse(Outcome.class, outcome);
        if (choice == null) {
            return html(HttpStatus.BAD_REQUEST, "Invalid choice", "<p>Choose Pay, Fail or Leave pending.</p>");
        }
        if (choice == Outcome.LEAVE_PENDING) {
            return redirect(session);
        }
        Method paidWith = null;
        if (choice == Outcome.PAY) {
            paidWith = parse(Method.class, method);
            if (paidWith != Method.UPI && paidWith != Method.CARD) {
                return html(HttpStatus.BAD_REQUEST, "Invalid method", "<p>Choose UPI or Card.</p>");
            }
        }
        GatewayEvent event;
        try {
            event = gateway.complete(sessionId, choice == Outcome.PAY ? GatewayStatus.SUCCEEDED : GatewayStatus.FAILED,
                    paidWith);
        } catch (IllegalStateException alreadySettled) {
            return finished(gateway.findSession(sessionId).orElse(session));
        }
        deliver(event);
        return redirect(session);
    }

    // ------------------------------------------------------------------ internals

    private Optional<Session> session(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            return Optional.empty();
        }
        return gateway.findSession(sessionId);
    }

    /** Posts the signed webhook; a failure is only logged (the reconciliation job resolves the payment, §3.1). */
    private void deliver(GatewayEvent event) {
        if (webhookUrl == null) {
            log.warn("Mock checkout: app.payment.mock.webhook-url is not set; webhook {} for {} not sent",
                    event.eventId(), event.paymentReference());
            return;
        }
        SignedWebhook webhook = gateway.signedWebhook(event);
        HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                .timeout(timeout)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(MockPaymentGateway.SIGNATURE_HEADER, webhook.signature())
                .POST(HttpRequest.BodyPublishers.ofByteArray(webhook.body()))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.warn("Mock checkout: webhook {} for {} answered HTTP {}", event.eventId(),
                        event.paymentReference(), response.statusCode());
            }
        } catch (IOException e) {
            log.warn("Mock checkout: webhook {} for {} not delivered: {}", event.eventId(), event.paymentReference(),
                    e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Mock checkout: webhook {} for {} interrupted", event.eventId(), event.paymentReference());
        }
    }

    private static ResponseEntity<String> redirect(Session session) {
        String target = session.returnUrl() == null ? "/" : session.returnUrl();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).build();
    }

    private static ResponseEntity<String> finished(Session session) {
        return html(HttpStatus.CONFLICT, "Checkout finished",
                "<p>This checkout is already " + HtmlUtils.htmlEscape(session.status().name()) + ".</p>");
    }

    private static ResponseEntity<String> html(HttpStatus status, String title, String body) {
        String page = """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>%s</title></head>
                <body>
                <h1>%s</h1>
                %s</body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), body);
        return ResponseEntity.status(status)
                .contentType(HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(page);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
