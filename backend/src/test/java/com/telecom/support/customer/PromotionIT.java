package com.telecom.support.customer;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.support.AbstractMySqlIT;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * E8 {@code GET /api/v1/customers/me/promotions} and E9 claim end to end (design 02-customer backend R1, R7, R8, R16,
 * §4, §7, §9; CUS-04, XC-06, NFR-REL). Test data: R__test_users_02_customer.sql (live: T_DATA_BOOST all plans,
 * T_OTT_POST POST_599 only; hidden: T_FESTIVE_OLD expired, T_INACTIVE; customer 1 POST_399 + PRE_199, customer 2
 * POST_599, customer 3 CLOSED with a TERMINATED subscription).
 */
class PromotionIT extends AbstractMySqlIT {

    private static final String PATH = "/api/v1/customers/me/promotions";
    private static final String XSRF = "promotion-xsrf-token";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    /** Test data has no claims; remove the ones a test made and restore the account states it changed. */
    @AfterEach
    void restore() {
        jdbc.update("""
                DELETE pc FROM promotion_claims pc
                JOIN subscriptions s ON s.id = pc.subscription_id
                WHERE s.msisdn IN ('+919900000001', '+919900000011', '+919900000002', '+919900000003')
                """, Map.of());
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE account_ref IN ('ACC-T-0001', 'ACC-T-0002')", Map.of());
    }

    // ------------------------------------------------------------------ E8

    @Test
    void listsOnlyLiveEligiblePromotionsWithTermsHash() throws Exception {
        MvcResult result = mvc.perform(get(PATH).cookie(login("+919900000001"))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        List<Map<String, Object>> body = json(result);
        // T_OTT_POST (POST_599 only), T_FESTIVE_OLD (expired) and T_INACTIVE are hidden
        assertThat(codes(body)).containsExactly("T_DATA_BOOST");
        Map<String, Object> promo = body.get(0);
        assertThat(promo).containsOnlyKeys("code", "title", "shortDescription", "eligibility", "terms", "termsHash",
                "validFrom", "validTo", "claimed", "eligibleSubscriptionIds");
        assertThat(promo.get("title")).isEqualTo("Extra 10 GB");
        assertThat(promo.get("shortDescription")).isEqualTo("10 GB extra data for 30 days");
        assertThat(promo.get("eligibility")).isEqualTo("All active subscriptions");
        assertThat(promo.get("terms")).isEqualTo(terms("T_DATA_BOOST"));
        assertThat(promo.get("termsHash")).isEqualTo(sha256(terms("T_DATA_BOOST")));
        assertThat((String) promo.get("validFrom")).endsWith("Z");
        assertThat((String) promo.get("validTo")).endsWith("Z");
        assertThat(promo.get("claimed")).isEqualTo(false);
        assertThat(ids(promo)).containsExactly(subscriptionId("+919900000001"), subscriptionId("+919900000011"));
    }

    @Test
    void planRestrictedPromotionIsShownOnlyToSubscribersOfTheListedPlan() throws Exception {
        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000002")))
                .andExpect(status().isOk()).andReturn());

        assertThat(codes(body)).containsExactly("T_DATA_BOOST", "T_OTT_POST");
        long sub = subscriptionId("+919900000002");
        assertThat(ids(body.get(0))).containsExactly(sub);
        assertThat(ids(body.get(1))).containsExactly(sub);
    }

    @Test
    void closedAccountWithoutActiveSubscriptionSeesNothingActionable() throws Exception {
        List<Map<String, Object>> body = json(mvc.perform(get(PATH).cookie(login("+919900000003")))
                .andExpect(status().isOk()).andReturn());

        assertThat(codes(body)).containsExactly("T_DATA_BOOST");
        assertThat(ids(body.get(0))).isEmpty();
    }

    @Test
    void listNeedsAuthenticationAndACustomer() throws Exception {
        assertProblem(mvc.perform(get(PATH)).andReturn(), 401, "unauthenticated", PATH);
        // INBOUND_AGENT holds promotion.read but is not a customer (§4)
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(mvc.perform(get(PATH).cookie(login(username))).andReturn(), 403, "forbidden", PATH);
        }
    }

    // ------------------------------------------------------------------ E9

    @Test
    void claimStoresTheTermsHashAndAudits() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000011");
        String hash = sha256(terms("T_DATA_BOOST"));
        long auditsBefore = auditCount("T_DATA_BOOST");

        MvcResult result = claim(session, "T_DATA_BOOST", "k-claim", sub, true, hash)
                .andExpect(status().isCreated()).andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        Map<String, Object> body = jsonObject(result);
        assertThat(body).containsOnlyKeys("promotionCode", "subscriptionId", "status", "claimedAt");
        assertThat(body.get("promotionCode")).isEqualTo("T_DATA_BOOST");
        assertThat(((Number) body.get("subscriptionId")).longValue()).isEqualTo(sub);
        assertThat(body.get("status")).isEqualTo("CLAIMED");
        assertThat((String) body.get("claimedAt")).endsWith("Z");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT pc.terms_hash, pc.status, u.username
                FROM promotion_claims pc
                JOIN promotions p ON p.id = pc.promotion_id
                JOIN users u ON u.id = pc.claimed_by_user_id
                WHERE p.code = 'T_DATA_BOOST' AND pc.subscription_id = :sub
                """, Map.of("sub", sub));
        assertThat(row.get("terms_hash")).isEqualTo(hash);
        assertThat(row.get("status")).isEqualTo("CLAIMED");
        assertThat(row.get("username")).isEqualTo("+919900000001");

        assertThat(auditCount("T_DATA_BOOST")).isEqualTo(auditsBefore + 1);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_username, outcome, details FROM audit_events
                WHERE action = 'PROMOTION_CLAIMED' AND entity_type = 'PROMOTION' AND entity_id = 'T_DATA_BOOST'
                ORDER BY id DESC LIMIT 1
                """, Map.of());
        assertThat(audit.get("actor_username")).isEqualTo("+919900000001");
        assertThat(audit.get("outcome")).isEqualTo("SUCCESS");
        Map<String, Object> details = objectMapper.readValue((String) audit.get("details"), new TypeReference<>() {});
        assertThat(details).isEqualTo(Map.of("subscriptionId", (int) sub, "termsHash", hash));

        // E8 now marks it claimed and offers only the subscription that has not claimed it
        List<Map<String, Object>> list = json(mvc.perform(get(PATH).cookie(session)).andReturn());
        assertThat(list.get(0).get("claimed")).isEqualTo(true);
        assertThat(ids(list.get(0))).containsExactly(subscriptionId("+919900000001"));
    }

    @Test
    void staleTermsHashOrTermsNotAcceptedIs422() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000001");
        String path = claims("T_DATA_BOOST");

        assertProblem(claim(session, "T_DATA_BOOST", "k-stale", sub, true, sha256("older terms")).andReturn(), 422,
                "business-rule", path);
        assertProblem(claim(session, "T_DATA_BOOST", "k-noaccept", sub, false, sha256(terms("T_DATA_BOOST")))
                .andReturn(), 422, "business-rule", path);
        assertThat(claimCount(sub)).isZero();
    }

    @Test
    void secondClaimForTheSameSubscriptionIs422() throws Exception {
        Cookie session = login("+919900000002");
        long sub = subscriptionId("+919900000002");
        String hash = sha256(terms("T_DATA_BOOST"));
        claim(session, "T_DATA_BOOST", "k-first", sub, true, hash).andExpect(status().isCreated());

        assertProblem(claim(session, "T_DATA_BOOST", "k-again", sub, true, hash).andReturn(), 422, "business-rule",
                claims("T_DATA_BOOST"));
        assertThat(claimCount(sub)).isEqualTo(1);
    }

    @Test
    void replayWithTheSameKeyReturnsTheSame201() throws Exception {
        Cookie session = login("+919900000002");
        long sub = subscriptionId("+919900000002");
        String hash = sha256(terms("T_OTT_POST"));
        Map<String, Object> first = jsonObject(claim(session, "T_OTT_POST", "k-replay", sub, true, hash)
                .andExpect(status().isCreated()).andReturn());
        Map<String, Object> second = jsonObject(claim(session, "T_OTT_POST", "k-replay", sub, true, hash)
                .andExpect(status().isCreated()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(claimCount(sub)).isEqualTo(1);
    }

    @Test
    void sameKeyAndBodyOnAnotherPromotionIsAnIdempotencyMismatch() throws Exception {
        Cookie session = login("+919900000002");
        long sub = subscriptionId("+919900000002");
        String hash = sha256(terms("T_DATA_BOOST"));
        claim(session, "T_DATA_BOOST", "k-path", sub, true, hash).andExpect(status().isCreated());

        assertProblem(claim(session, "T_OTT_POST", "k-path", sub, true, hash).andReturn(), 422,
                "idempotency-mismatch", claims("T_OTT_POST"));
        assertThat(claimCount(sub)).isEqualTo(1);
    }

    @Test
    void expiredInactiveOrOtherPlanPromotionIs422() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000001");
        for (String code : List.of("T_FESTIVE_OLD", "T_INACTIVE", "T_OTT_POST")) {
            assertProblem(claim(session, code, "k-inel-" + code, sub, true, sha256(terms(code))).andReturn(), 422,
                    "business-rule", claims(code));
        }
        assertThat(claimCount(sub)).isZero();
    }

    @Test
    void unknownPromotionOrAnotherCustomersSubscriptionIsNotFound() throws Exception {
        Cookie session = login("+919900000001");
        String hash = sha256(terms("T_DATA_BOOST"));
        assertProblem(claim(session, "T_NO_SUCH", "k-unknown", subscriptionId("+919900000001"), true, hash)
                .andReturn(), 404, "not-found", claims("T_NO_SUCH"));

        long other = subscriptionId("+919900000002");
        assertProblem(claim(session, "T_DATA_BOOST", "k-idor", other, true, hash).andReturn(), 404, "not-found",
                claims("T_DATA_BOOST"));
        assertThat(claimCount(other)).isZero();
    }

    @Test
    void suspendedOrClosedAccountCannotClaim() throws Exception {
        // OQ 12: a SUSPENDED account cannot claim (customer 1, otherwise eligible)
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000001");
        String hash = sha256(terms("T_DATA_BOOST"));
        jdbc.update("UPDATE accounts SET status = 'SUSPENDED' WHERE account_ref = 'ACC-T-0001'", Map.of());
        assertProblem(claim(session, "T_DATA_BOOST", "k-susp", sub, true, hash).andReturn(), 422, "business-rule",
                claims("T_DATA_BOOST"));
        assertThat(claimCount(sub)).isZero();

        long terminated = jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = '+919900000003'",
                Map.of(), Long.class);
        assertProblem(claim(login("+919900000003"), "T_DATA_BOOST", "k-closed", terminated, true, hash).andReturn(),
                422, "business-rule", claims("T_DATA_BOOST"));
        assertThat(claimCount(terminated)).isZero();
    }

    @Test
    void missingKeyOrInvalidBodyIs400() throws Exception {
        Cookie session = login("+919900000001");
        long sub = subscriptionId("+919900000001");
        String path = claims("T_DATA_BOOST");
        assertProblem(mvc.perform(post(path).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sub, true, sha256(terms("T_DATA_BOOST"))))).andReturn(),
                400, "bad-request", path);
        assertProblem(claim(session, "T_DATA_BOOST", "k-bad", sub, true, "NOT-A-HASH").andReturn(), 400,
                "validation", path);
        assertThat(claimCount(sub)).isZero();
    }

    @Test
    void claimNeedsAuthenticationAndACustomer() throws Exception {
        long sub = subscriptionId("+919900000001");
        String path = claims("T_DATA_BOOST");
        String hash = sha256(terms("T_DATA_BOOST"));
        assertProblem(mvc.perform(post(path).cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF)
                        .header("Idempotency-Key", "k-anon").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sub, true, hash))).andReturn(),
                401, "unauthenticated", path);
        for (String username : List.of("T-IN", "T-OUT", "T-BUS", "+919900000004")) {
            assertProblem(claim(login(username), "T_DATA_BOOST", "k-403-" + username, sub, true, hash).andReturn(),
                    403, "forbidden", path);
        }
        assertThat(claimCount(sub)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> codes(List<Map<String, Object>> body) {
        return body.stream().map(p -> (String) p.get("code")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Map<String, Object> promotion) {
        return ((List<Number>) promotion.get("eligibleSubscriptionIds")).stream().map(Number::longValue).toList();
    }

    private String terms(String code) {
        return jdbc.queryForObject("SELECT terms FROM promotions WHERE code = :code", Map.of("code", code),
                String.class);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private long subscriptionId(String msisdn) {
        return jdbc.queryForObject("SELECT id FROM subscriptions WHERE msisdn = :msisdn AND status <> 'TERMINATED'",
                Map.of("msisdn", msisdn), Long.class);
    }

    private long claimCount(long subscriptionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM promotion_claims WHERE subscription_id = :sub",
                Map.of("sub", subscriptionId), Long.class);
    }

    private long auditCount(String code) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'PROMOTION_CLAIMED' AND entity_type = 'PROMOTION' AND entity_id = :code
                """, Map.of("code", code), Long.class);
    }

    private static String claims(String code) {
        return PATH + "/" + code + "/claims";
    }

    private String body(long subscriptionId, boolean acceptTerms, String termsHash) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscriptionId", subscriptionId);
        body.put("acceptTerms", acceptTerms);
        body.put("termsHash", termsHash);
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions claim(Cookie session, String code, String key, long subscriptionId, boolean acceptTerms,
                                String termsHash) throws Exception {
        return mvc.perform(post(claims(code)).cookie(session, new Cookie("XSRF-TOKEN", XSRF))
                .header("X-XSRF-TOKEN", XSRF)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(subscriptionId, acceptTerms, termsHash)));
    }

    private Map<String, Object> jsonObject(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    private List<Map<String, Object>> json(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
    }

    /** Logs in like the SPA (XSRF cookie echoed in the header) from a fresh client IP; returns the session cookie. */
    private Cookie login(String username) throws Exception {
        int n = IPS.incrementAndGet();
        String ip = "10.24." + (n / 250) + "." + (n % 250 + 1);
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
}
