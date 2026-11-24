package com.telecom.support.common;

import static com.telecom.support.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.ApiErrorProbeController;
import com.telecom.support.support.ValidationProbeController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Gap pass: the exact 00-overview §4 ProblemDetail shape (key set, type, instance, correlationId, no internals) for every
 * error status the app can produce today. 429 and the login 401 are in {@code AuthContractIT}; 503
 * {@code dependency-unavailable} has no producer yet (integrations arrive later).
 */
@Import({ValidationProbeController.class, ApiErrorProbeController.class})
class ProblemShapeIT extends AbstractMySqlIT {

    private static final String PROBE = "/api/v1/test-probe";
    private static final String ERRORS = "/api/v1/test-probe-errors";
    private static final RequestPostProcessor STAFF = user("probe-user");
    private static final RequestPostProcessor XSRF = request -> {
        request.setCookies(new Cookie("XSRF-TOKEN", "shape-token"));
        request.addHeader("X-XSRF-TOKEN", "shape-token");
        return request;
    };

    @Test
    void validation400HasFieldErrors() throws Exception {
        var body = assertProblem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"email\":\"x\"}")).andReturn(),
                400, "validation", PROBE + "/validate");
        assertThat(body.get("errors")).asList().isNotEmpty();
    }

    @Test
    void badRequest400() throws Exception {
        assertProblem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":")).andReturn(),
                400, "bad-request", PROBE + "/validate");
    }

    @Test
    void unauthenticated401OnAKnownAndAnUnknownApiPath() throws Exception {
        assertProblem(mvc.perform(get(PROBE + "/ok")).andReturn(), 401, "unauthenticated", PROBE + "/ok");
        // an anonymous caller must not learn that a path does not exist
        assertProblem(mvc.perform(get("/api/v1/does-not-exist")).andReturn(), 401, "unauthenticated",
                "/api/v1/does-not-exist");
    }

    @Test
    void forbidden403ForPermissionAndForCsrf() throws Exception {
        assertProblem(mvc.perform(get(PROBE + "/denied").with(STAFF)).andReturn(), 403, "forbidden", PROBE + "/denied");
        assertProblem(mvc.perform(post(PROBE + "/validate").with(STAFF)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"email\":\"a@b.co\"}")).andReturn(),
                403, "forbidden", PROBE + "/validate");
    }

    @Test
    void notFound404ForAnUnknownApiPathWhenSignedIn() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/does-not-exist").with(STAFF)).andReturn(), 404, "not-found",
                "/api/v1/does-not-exist");
    }

    @Test
    void methodNotAllowed405() throws Exception {
        assertProblem(mvc.perform(delete(PROBE + "/ok").with(STAFF).with(XSRF)).andReturn(), 405,
                "method-not-allowed", PROBE + "/ok");
    }

    @Test
    void conflict409DoesNotLeakTheExceptionMessage() throws Exception {
        var body = assertProblem(mvc.perform(get(ERRORS + "/conflict").with(STAFF)).andReturn(), 409, "conflict",
                ERRORS + "/conflict");
        assertThat(body.toString()).doesNotContain(ApiErrorProbeController.INTERNAL_TEXT);
    }

    @Test
    void businessRule422DoesNotLeakTheExceptionMessage() throws Exception {
        var body = assertProblem(mvc.perform(get(ERRORS + "/business-rule").with(STAFF)).andReturn(), 422,
                "business-rule", ERRORS + "/business-rule");
        assertThat(body.toString()).doesNotContain(ApiErrorProbeController.INTERNAL_TEXT);
    }

    @Test
    void idempotencyMismatch422() throws Exception {
        var body = assertProblem(mvc.perform(get(ERRORS + "/idempotency-mismatch").with(STAFF)).andReturn(), 422,
                "idempotency-mismatch", ERRORS + "/idempotency-mismatch");
        assertThat(body.toString()).doesNotContain(ApiErrorProbeController.INTERNAL_TEXT);
    }

    @Test
    void payloadTooLarge413() throws Exception {
        String big = "{\"name\":\"" + "x".repeat(1024 * 1024 + 1) + "\",\"email\":\"a@example.com\"}";
        assertProblem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                .contentType(MediaType.APPLICATION_JSON).content(big)).andReturn(),
                413, "payload-too-large", PROBE + "/validate");
    }

    @Test
    void unsupportedMediaType415() throws Exception {
        assertProblem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                .contentType(MediaType.TEXT_PLAIN).content("name=A")).andReturn(),
                415, "unsupported-media-type", PROBE + "/validate");
    }

    @Test
    void internal500HasNoStackTraceOrMessage() throws Exception {
        var body = assertProblem(mvc.perform(get(PROBE + "/boom").with(STAFF)).andReturn(), 500, "internal",
                PROBE + "/boom");
        assertThat(body.toString()).doesNotContain(ValidationProbeController.SECRET);
    }
}
