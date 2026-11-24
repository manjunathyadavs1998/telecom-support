package com.telecom.support.identity;

import com.telecom.support.common.error.ProblemDetailFactory;
import com.telecom.support.common.error.ProblemDetailFactory.Problem;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Security chain for the same-origin SPA (ADR 0003, 01 backend §4–§4.4).
 *
 * <ul>
 *   <li>CSRF: cookie repository ({@code XSRF-TOKEN}, readable by the SPA) with the plain request handler, so the
 *       {@code X-XSRF-TOKEN} header equals the cookie value; {@link CsrfCookieFilter} writes the cookie and
 *       {@link AuthController} rotates it on login and logout.</li>
 *   <li>Session login: {@link AuthController} stores the context through the shared {@link SecurityContextRepository}
 *       (Spring Session JDBC); {@link SessionPolicyFilter} enforces the absolute lifetime and the DISABLED re-check.</li>
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
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailFactory problems,
                                            CsrfTokenRepository csrfTokenRepository,
                                            SecurityContextRepository securityContextRepository,
                                            UserRepository users, Clock clock,
                                            @Value("${app.session.absolute-timeout}") Duration absoluteTimeout)
            throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .addFilterAfter(new SessionPolicyFilter(users, clock, absoluteTimeout), SecurityContextHolderFilter.class)
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
