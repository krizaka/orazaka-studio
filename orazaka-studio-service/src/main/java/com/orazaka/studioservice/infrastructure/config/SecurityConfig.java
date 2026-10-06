package com.orazaka.studioservice.infrastructure.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless security for the studio service.
 *
 * <p>The session JWT is verified locally against the shared HS256 secret and its {@code roles}
 * claim becomes the authorities, so no request pays an identity hop.
 *
 * <p>URL rules stay coarse (authenticated vs not) on purpose: per-resource access is a method
 * concern expressed with {@code @PreAuthorize} on the controllers, never a class name or a URL
 * convention (ERR-128). Authoring is therefore gated by {@code hasRole('ADMIN')} on the method, and
 * every read is additionally scoped by the caller's actor id — a URL rule cannot express "your own
 * installation", which is the isolation that actually matters here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SessionJwtProperties.class)
public class SecurityConfig {

  /**
   * Local HS256 decoder over the shared identity secret.
   *
   * @param properties the shared-secret wiring
   * @return a decoder that validates a session JWT without calling identity
   */
  @Bean
  JwtDecoder identityJwtDecoder(SessionJwtProperties properties) {
    return NimbusJwtDecoder.withSecretKey(
            new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  /**
   * Maps the identity JWT's {@code roles} claim straight onto authorities.
   *
   * @return the converter, with no authority prefix — identity already emits {@code ROLE_*}
   */
  @Bean
  JwtAuthenticationConverter studioJwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  /**
   * The filter chain: everything under {@code /api/v1/studios} requires a valid session JWT.
   *
   * @param http the builder
   * @param converter the roles-claim converter
   * @return the built chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @SuppressWarnings(
      "java:S4502") // Justified: CSRF disabled for a stateless, token-authenticated API.
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info", "/error")
                    .permitAll()
                    // Authenticated, not merely unrouted. The edge not routing /internal/** is
                    // topology, and topology holds only as long as the topology does: one SSRF in
                    // an estate where every pod reaches every pod turns this into an anonymous
                    // call. The edge rule stays as the second layer (ADR-035).
                    //
                    // "SERVICE", not "SCOPE_internal": the converter above is
                    // setAuthoritiesClaimName("roles") with an empty prefix, so the authority IS
                    // the raw claim value. A prefixed matcher fails closed against a correct
                    // token, and the tempting repair is to weaken the matcher.
                    .requestMatchers("/internal/v1/**")
                    .hasAuthority("SERVICE")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
    return http.build();
  }
}
