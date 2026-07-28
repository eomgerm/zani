package com.a105.zani.auth.infrastructure.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.a105.zani.common.config.CorsProperties;
import com.a105.zani.common.error.handler.ApiErrorResponseWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("accessTokenJwtDecoder") JwtDecoder accessTokenJwtDecoder,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            CorsProperties corsProperties,
            ApiErrorResponseWriter responseWriter)
            throws Exception {
        RefreshOriginFilter refreshOriginFilter = new RefreshOriginFilter(corsProperties, responseWriter);

        // LiveKit webhook 경로의 Authorization 헤더는 사용자 JWT가 아니라 LiveKit 서명 토큰이다. resource-server의
        // Bearer 필터가 이를 ZANI 토큰으로 오해해 permitAll보다 먼저 401을 내지 않도록 이 경로에서는 토큰을 해석하지 않는다.
        DefaultBearerTokenResolver defaultBearerTokenResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver bearerTokenResolver = request -> HttpMethod.POST.matches(request.getMethod())
                        && "/api/v1/internal/recordings/webhook".equals(request.getRequestURI())
                ? null
                : defaultBearerTokenResolver.resolve(request);

        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/login/google")
                        .permitAll()
                        // LiveKit webhook은 사용자 JWT가 아니라 LiveKit 서명 토큰으로 인증한다(컨트롤러에서 검증, 불일치 401).
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/recordings/webhook")
                        .permitAll()
                        // Egress 노드가 강사 오디오를 밀어 넣는 내부 WebSocket. 사용자 JWT가 아니라
                        // 핸들러가 공유 시크릿으로 검증한다.
                        .requestMatchers("/internal/audio/**")
                        .permitAll()
                        .requestMatchers(
                                "/actuator/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(refreshOriginFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.decoder(accessTokenJwtDecoder))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}
