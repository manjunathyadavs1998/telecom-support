package com.telecom.support.xxx;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class XxxControllerIT extends AbstractWebIntegrationTest {

    @Test
    void customerListsOwnXxx() throws Exception {
        mvc.perform(get("/api/v1/me/xxx").with(user("customer1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void validationErrorReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/me/xxx").with(user("customer1")).with(csrf())
                        .header("Idempotency-Key", "it-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void forbiddenRoleGets403() throws Exception {
        mvc.perform(get("/api/v1/me/xxx").with(user("business1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/v1/me/xxx")).andExpect(status().isUnauthorized());
    }
}
