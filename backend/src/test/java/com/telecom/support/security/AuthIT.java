package com.telecom.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.AbstractWebIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthIT extends AbstractWebIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";

    @Test
    void customerLogsInAndSessionReturnsPermissions() throws Exception {
        MvcResult login = mvc.perform(post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"+919876543210\",\"password\":\"Password@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();

        mvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("John Doe"));
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhichFieldWasWrong() throws Exception {
        mvc.perform(post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inbound2\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"inbound1\",\"password\":\"Password@123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankCredentialsFailValidationWithFieldErrors() throws Exception {
        mvc.perform(post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void anonymousApiCallGets401ProblemDetailWithCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("X-Correlation-Id", "test-correlation-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "test-correlation-123"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation-123"));
    }

    @Test
    void csrfEndpointIssuesToken() throws Exception {
        // Header/cookie names are asserted against the running app (Spring Security Test swaps the token repository).
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void logoutEndsSession() throws Exception {
        MvcResult login = mvc.perform(post(LOGIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"business1\",\"password\":\"Password@123\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");

        mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }
}
