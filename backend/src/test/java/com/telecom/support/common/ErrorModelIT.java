package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.support.AbstractMySqlIT;
import com.telecom.support.support.ValidationProbeController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** B3: 00-overview §4 error model and §5 correlation id, baseline security chain (01 backend §4.2–§4.4, §8). */
@Import(ValidationProbeController.class)
class ErrorModelIT extends AbstractMySqlIT {

    private static final String PROBE = "/api/v1/test-probe";
    private static final String TYPE = "https://telecom.example/problems/";
    private static final RequestPostProcessor STAFF = user("probe-user");

    /**
     * What the SPA sends (01 backend §4.2): the {@code XSRF-TOKEN} cookie value echoed in {@code X-XSRF-TOKEN}. Used instead
     * of spring-security-test's {@code csrf()}, which swaps the shared context's token repository for a test one.
     */
    private static final RequestPostProcessor XSRF = request -> {
        String token = "test-xsrf-token";
        request.setCookies(new Cookie("XSRF-TOKEN", token));
        request.addHeader("X-XSRF-TOKEN", token);
        return request;
    };

    @Test
    void validationFailureIs400WithFieldErrors() throws Exception {
        problem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"email\":\"not-an-email\"}")),
                400, "validation", PROBE + "/validate")
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void malformedJsonIs400BadRequest() throws Exception {
        problem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":")),
                400, "bad-request", PROBE + "/validate")
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void unknownJsonFieldsAreIgnored() throws Exception {
        mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"email\":\"a@example.com\",\"extra\":1}"))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedMethodIs405WithAllowHeader() throws Exception {
        problem(mvc.perform(delete(PROBE + "/ok").with(STAFF).with(XSRF)), 405, "method-not-allowed", PROBE + "/ok")
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void bodyOverMaxRequestSizeIs413() throws Exception {
        String big = "{\"name\":\"" + "x".repeat(1024 * 1024 + 1) + "\",\"email\":\"a@example.com\"}";
        problem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                        .contentType(MediaType.APPLICATION_JSON).content(big)),
                413, "payload-too-large", PROBE + "/validate");
    }

    @Test
    void nonJsonBodyIs415() throws Exception {
        problem(mvc.perform(post(PROBE + "/validate").with(STAFF).with(XSRF)
                        .contentType(MediaType.TEXT_PLAIN).content("name=A")),
                415, "unsupported-media-type", PROBE + "/validate");
    }

    @Test
    void unmappedExceptionIs500WithGenericDetail() throws Exception {
        MvcResult result = problem(mvc.perform(get(PROBE + "/boom").with(STAFF)), 500, "internal", PROBE + "/boom")
                .andExpect(jsonPath("$.detail").value(containsString("correlation id")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(ValidationProbeController.SECRET, "IllegalStateException", "at com.");
    }

    @Test
    void methodLevelAccessDeniedIs403() throws Exception {
        problem(mvc.perform(get(PROBE + "/denied").with(STAFF)), 403, "forbidden", PROBE + "/denied");
    }

    @Test
    void chainUnauthenticatedIs401ProblemWithoutRedirect() throws Exception {
        problem(mvc.perform(get(PROBE + "/ok")), 401, "unauthenticated", PROBE + "/ok")
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void chainCsrfFailureIs403Problem() throws Exception {
        problem(mvc.perform(post(PROBE + "/validate").with(STAFF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"email\":\"a@example.com\"}")),
                403, "forbidden", PROBE + "/validate");
    }

    @Test
    void csrfHeaderEqualToTheCookieValueIsAccepted() throws Exception {
        Cookie xsrf = mvc.perform(get(PROBE + "/ok").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        mvc.perform(post(PROBE + "/validate").with(STAFF).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"email\":\"a@example.com\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(PROBE + "/validate").with(STAFF).cookie(xsrf).header("X-XSRF-TOKEN", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validIncomingCorrelationIdIsEchoed() throws Exception {
        String id = "abc-123-XYZ";
        mvc.perform(get(PROBE + "/ok").header("X-Correlation-Id", id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", id))
                .andExpect(jsonPath("$.correlationId").value(id));
        mvc.perform(get(PROBE + "/ok").with(STAFF).header("X-Correlation-Id", id))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", id));
    }

    @Test
    void invalidIncomingCorrelationIdIsReplaced() throws Exception {
        for (String bad : new String[] {"bad id!", "x".repeat(65), "a\r\nInjected: 1"}) {
            MvcResult result = mvc.perform(get(PROBE + "/boom").with(STAFF).header("X-Correlation-Id", bad))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string("X-Correlation-Id", not(bad)))
                    .andReturn();
            String id = result.getResponse().getHeader("X-Correlation-Id");
            assertThat(id).matches("[A-Za-z0-9-]{1,64}");
            assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"" + id + "\"");
        }
    }

    @Test
    void healthIsPublicAndOtherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").with(STAFF)).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans").with(STAFF)).andExpect(status().isNotFound());
    }

    /** Common §4 shape: status, type, instance, problem+json, correlationId equal to the response header. */
    private static ResultActions problem(ResultActions actions, int status, String type, String instance)
            throws Exception {
        MvcResult result = actions
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.type").value(TYPE + type))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();
        String id = result.getResponse().getHeader("X-Correlation-Id");
        actions.andExpect(jsonPath("$.correlationId").value(id));
        return actions;
    }
}
