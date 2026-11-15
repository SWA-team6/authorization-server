package com.team6.cils.keymanagement.infrastructure;
import java.nio.file.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class FileSigningConfiguration {
    @Bean
    JWKSource<SecurityContext> jwkSource(@Value("${cils.signing.jwk-file}") String file) {
        try {
            var key = RSAKey.parse(Files.readString(Path.of(file)));
            if (!key.isPrivate() || key.size() < 2048 || key.getKeyID() == null || key.getKeyID().isBlank()) {
                throw new IllegalArgumentException();
            }
            key.toRSAPrivateKey();
            return new ImmutableJWKSet<>(new JWKSet(key));
        } catch (Exception ignored) {
            // Parser exceptions may contain private input: never propagate them.
            throw new IllegalStateException("A readable private RSA JWK with kid and >= 2048 bits is required");
        }
    }
}