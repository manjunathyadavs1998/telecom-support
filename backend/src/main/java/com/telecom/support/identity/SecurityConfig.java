package com.telecom.support.identity;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Baseline security chain for the same-origin SPA (ADR 0003, 01 backend §4.2–§4.4). Session login, session policy and
 * method security are added by later tasks.
 *
 * <ul>
 *   <li>CSRF: cookie repository ({@code XSRF-TOKEN}, readable by the SPA) with the plain request handler, so the
 *       {@code X-XSRF-TOKEN} header equals the cookie value; {@link CsrfCookieFilter} writes the cookie.</li>
 *   <li>Public: {@code /api/v1/auth/csrf}, {@code /api/v1/auth/login}, {@code /actuator/health}, static SPA assets,
 *       API docs (springdoc is enabled only in the {@code dev} profile). All other {@code /api/**} and
 *       {@code /actuator/**} need a session.</li>
 *   <li>401 and 403 (URL-level and CSRF) are JSON problems from {@link ProblemDetailFactory}; never a redirect.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailFactory problems) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/**", "/actuator/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                    problems.write(request, response, Problem.UNAUTHENTICATED))
                .accessDeniedHandler((request, response, e) ->
                    problems.write(request, response, Problem.FORBIDDEN)))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
