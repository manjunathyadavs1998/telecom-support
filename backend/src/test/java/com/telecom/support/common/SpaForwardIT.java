package com.telecom.support.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.telecom.support.support.AbstractMySqlIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * F1: SPA forward (design 01-foundation backend §12 E2.1; XC-02, ADR 0001). Runs on a real server so the forward to
 * {@code index.html} is actually executed; the fixture {@code index.html} and {@code assets/app-fixture.js} come from
 * {@code src/test/resources/static}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.locations=classpath:db/migration,classpath:db/test-data")
class SpaForwardIT extends AbstractMySqlIT {

    private static final RequestPostProcessor STAFF = user("spa-user");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    int port;

    @Test
    void spaRoutesServeIndexHtmlWithNoCache() throws Exception {
        for (String path : new String[] {"/outbound", "/customer/plan", "/customer/plan/"}) {
            HttpResponse<String> response = fetch(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).as(path).hasValueSatisfying(
                    v -> assertThat(v).startsWith("text/html"));
            assertThat(response.headers().firstValue("Cache-Control")).as(path).hasValue("no-cache");
            assertThat(response.body()).as(path).contains("spa-fixture");
        }
    }

    @Test
    void hashedAssetsAreImmutable() throws Exception {
        HttpResponse<String> response = fetch("/assets/app-fixture.js");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=31536000, immutable");
    }

    @Test
    void unknownApiPathKeepsItsProblemDetail() throws Exception {
        HttpResponse<String> anonymous = fetch("/api/v1/nope");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertThat(anonymous.body()).doesNotContain("spa-fixture");

        mvc.perform(get("/api/v1/nope").with(STAFF))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/v1/deep/nope/path").with(STAFF))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void actuatorAndErrorAreNotForwarded() throws Exception {
        HttpResponse<String> actuator = fetch("/actuator/nope");
        assertThat(actuator.statusCode()).isEqualTo(401);
        assertThat(actuator.body()).doesNotContain("spa-fixture");
        mvc.perform(get("/actuator/nope").with(STAFF))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));

        assertThat(fetch("/error").body()).doesNotContain("spa-fixture");
        mvc.perform(get("/error").with(STAFF)).andExpect(forwardedUrl(null));
        for (String path : new String[] {"/v3/nope", "/swagger-ui/nope"}) {
            assertThat(fetch(path).body()).as(path).doesNotContain("spa-fixture");
        }
    }

    @Test
    void missingFileIs404AndNonGetIsNotForwarded() throws Exception {
        HttpResponse<String> missing = fetch("/missing.js");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain("spa-fixture");
        assertThat(fetch("/outbound/report.csv").statusCode()).isEqualTo(404);

        mvc.perform(post("/outbound").with(STAFF)).andExpect(forwardedUrl(null));
    }

    private HttpResponse<String> fetch(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
