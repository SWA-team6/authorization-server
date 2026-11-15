package com.team6.cils.protocol.infrastructure;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class PostgresProtocolConfiguration {
    @Bean
    OAuth2AuthorizationService authorizations(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }
    @Bean
    OAuth2AuthorizationConsentService consents(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
    }
}