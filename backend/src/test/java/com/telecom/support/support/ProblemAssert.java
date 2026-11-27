package com.telecom.support.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Strict 00-overview §4 ProblemDetail shape check shared by the error-contract ITs: problem+json, exact key set, the
 * type for the row, {@code instance} = request path, {@code correlationId} = the {@code X-Correlation-Id} header, and
 * nothing internal (stack frames, class names, SQL) in the body.
 */
public final class ProblemAssert {

    public static final String TYPE_BASE = "https://telecom.example/problems/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "correlationId", "errors");

    private ProblemAssert() {
    }

    public static Map<String, Object> assertProblem(MvcResult result, int status, String typeSuffix, String instance)
            throws Exception {
        return assertProblem(result, status, typeSuffix, instance, Set.of());
    }

    /** As above, also allowing the problem type's own {@code extensions} (e.g. 03 {@code ReactivationProblem}). */
    public static Map<String, Object> assertProblem(MvcResult result, int status, String typeSuffix, String instance,
                                                    Set<String> extensions) throws Exception {
        Set<String> allowed = new HashSet<>(ALLOWED_KEYS);
        allowed.addAll(extensions);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content type " + result.getResponse().getContentType()).isTrue();
        String raw = result.getResponse().getContentAsString();
        Map<String, Object> body = MAPPER.readValue(raw, new TypeReference<>() {});
        assertThat(body.keySet()).isSubsetOf(allowed)
                .contains("type", "title", "status", "detail", "instance", "correlationId");
        assertThat(body.get("status")).isEqualTo(status);
        assertThat(body.get("type")).isEqualTo(TYPE_BASE + typeSuffix);
        assertThat(body.get("title")).asString().isNotBlank();
        assertThat(body.get("detail")).asString().isNotBlank();
        assertThat(body.get("instance")).isEqualTo(instance);
        String header = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(header).matches("[A-Za-z0-9-]{1,64}");
        assertThat(body.get("correlationId")).isEqualTo(header);
        if (!typeSuffix.equals("validation")) {
            assertThat(body).doesNotContainKey("errors");
        }
        assertThat(raw).doesNotContain("Exception", "stackTrace", "at com.", "org.springframework", "SELECT ");
        return body;
    }
}
