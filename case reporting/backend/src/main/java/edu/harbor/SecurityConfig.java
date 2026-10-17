package edu.harbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SecurityConfig {
    @Bean
    JdbcUserDetailsManager userDetailsService(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(jdbc.getDataSource());
        manager.setUsersByUsernameQuery("SELECT username, password, enabled FROM app_users WHERE username = ?");
        manager.setAuthoritiesByUsernameQuery("SELECT username, authority FROM authorities WHERE username = ?");
        return manager;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(JdbcUserDetailsManager users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String configuredOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(configuredOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DaoAuthenticationProvider provider,
                                             org.springframework.jdbc.core.JdbcTemplate jdbc,
                                             ObjectMapper mapper) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/api/reports", "/api/status").permitAll()
                        .requestMatchers("/api/patterns/**").hasRole("COMMITTEE")
                        .requestMatchers("/api/cases/**").hasRole("STAFF")
                        .requestMatchers("/api/admin/**").hasAnyRole("STAFF", "COMMITTEE")
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"message\":\"Please sign in to continue.\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"message\":\"You do not have permission to view this information.\"}");
                        }))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            jdbc.update("INSERT INTO audit_log(id, actor, action) VALUES (?, ?, ?)",
                                    java.util.UUID.randomUUID(), authentication.getName(), "STAFF_SIGN_IN");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"ok\":true}");
                        })
                        .failureHandler((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write("{\"message\":\"Sign-in details were not recognized.\"}");
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (authentication != null) {
                                jdbc.update("INSERT INTO audit_log(id, actor, action) VALUES (?, ?, ?)",
                                        java.util.UUID.randomUUID(), authentication.getName(), "STAFF_SIGN_OUT");
                            }
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .maximumSessions(2))
                .addFilterBefore(new RateLimitFilter(mapper), UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .addHeaderWriter((request, response) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setHeader("Cache-Control", "no-store, private");
                                response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
                            }
                        })
                        .cacheControl(Customizer.withDefaults()));
        return http.build();
    }

    static class RateLimitFilter extends OncePerRequestFilter {
        private record Window(Instant started, AtomicInteger requests) { }
        private final Cache<String, Window> windows = Caffeine.newBuilder()
                .maximumSize(20_000).expireAfterWrite(Duration.ofHours(2)).build();
        private final ObjectMapper mapper;

        RateLimitFilter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getRequestURI();
            int limit;
            int seconds;
            if (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/reports")) {
                limit = 5;
                seconds = 3600;
            } else if (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/status")) {
                limit = 12;
                seconds = 60;
            } else if (HttpMethod.POST.matches(request.getMethod()) && path.equals("/api/auth/login")) {
                limit = 10;
                seconds = 900;
            } else {
                chain.doFilter(request, response);
                return;
            }
            String key = path + "|" + request.getRemoteAddr();
            Instant now = Instant.now();
            Window window = windows.asMap().compute(key, (ignored, old) ->
                    old == null || Duration.between(old.started(), now).toSeconds() >= seconds
                            ? new Window(now, new AtomicInteger()) : old);
            if (window.requests().incrementAndGet() > limit) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                mapper.writeValue(response.getOutputStream(), java.util.Map.of("message", "Please wait a little before trying again."));
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
