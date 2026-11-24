package com.telecom.support.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document = the typed contract the frontend generates its types from (BRD §14 #10, NFR-MAINT). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI telecomSupportOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Telecom Customer Support API").version("v1")
                        .description("Session-cookie authenticated API. Mutating calls need the X-XSRF-TOKEN header."))
                .components(new Components().addSecuritySchemes("session",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("SESSION")))
                .addSecurityItem(new SecurityRequirement().addList("session"));
    }

    /**
     * Response records always serialise every component, so every property of a {@code *Response} schema (and
     * of {@code PageResponse*}) is marked required. Fields that can be null must say so with
     * {@code @Schema(nullable = true)}; the generated TypeScript then reads {@code T | null}, never {@code T | undefined}.
     */
    @Bean
    OpenApiCustomizer responsePropertiesRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Map.Entry<String, Schema> entry : openApi.getComponents().getSchemas().entrySet()) {
                String name = entry.getKey();
                Schema<?> schema = entry.getValue();
                boolean isResponse = name.endsWith("Response") || name.startsWith("PageResponse");
                if (isResponse && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            }
        };
    }
}
