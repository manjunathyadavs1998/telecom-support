package com.telecom.support.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.support.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Session-based security for the same-origin SPA (ADR 0003). Every API needs authentication; fine-grained
 * authorization is declared per endpoint with {@code @PreAuthorize} on BRD §8 permissions (BR-10).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                            SecurityContextRepository contextRepository,
                                            @Value("${app.security.api-docs-public:false}") boolean apiDocsPublic) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.sameSite("Lax").path("/"));

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                // Payment provider callbacks are server-to-server and authenticated by HMAC signature instead.
                .ignoringRequestMatchers("/api/v1/payments/webhook"))
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .sessionManagement(sm -> sm.sessionFixation(f -> f.changeSessionId()))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf", "/api/v1/payments/webhook").permitAll();
                auth.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                if (apiDocsPublic) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }
                auth.requestMatchers("/api/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").authenticated();
                auth.anyRequest().permitAll(); // static SPA assets and client-side routes
            })
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    writeProblem(res, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in to continue"))
                .accessDeniedHandler((req, res, e) ->
                    writeProblem(res, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action")))
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .deleteCookies("SESSION")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable());
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        ProviderManager manager = new ProviderManager(provider);
        manager.setEraseCredentialsAfterAuthentication(true);
        return manager;
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String code,
                                     String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setType(URI.create("https://errors.telecom-support.local/" + code.toLowerCase()));
        pd.setProperty("code", code);
        pd.setProperty("correlationId", CorrelationIdFilter.current());
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
