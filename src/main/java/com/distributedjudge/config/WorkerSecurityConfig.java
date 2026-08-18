package com.distributedjudge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The worker role has no business HTTP endpoints (JudgeController/AuthController
 * are gateway-only) — its only HTTP surface is actuator health/metrics, which
 * carries no user data, so it's permitted without the token-based auth that
 * SecurityConfig sets up for the gateway. Scoped to "worker & !gateway" (not
 * just "!gateway") so the default/combined profile — where both roles run in
 * one process — still gets SecurityConfig's real protection instead of this
 * permissive chain; both would otherwise register competing SecurityFilterChain
 * beans that each match "/**".
 */
@Configuration
@Profile("worker & !gateway")
public class WorkerSecurityConfig {
    @Bean
    SecurityFilterChain workerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
