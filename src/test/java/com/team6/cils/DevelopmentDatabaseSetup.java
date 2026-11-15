package com.team6.cils;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit development fixture tool, not an administrative API or startup initializer. */
public final class DevelopmentDatabaseSetup {
    public static void main(String[] args) throws Exception {
        String password = required("CILS_DEV_PASSWORD");
        String secret = required("CILS_DEV_CLIENT_SECRET");
        Path keyFile = Path.of(required("CILS_SIGNING_JWK_FILE"));
        if (!Files.exists(keyFile)) {
            var key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(keyFile, key.toJSONString());
        }
        var dataSource = new DriverManagerDataSource(required("CILS_DB_URL"),
            required("CILS_DB_USERNAME"), required("CILS_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).defaultSchema("cils").cleanDisabled(true).load().migrate();
        var clients = new JdbcRegisteredClientRepository(jdbc);
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        new TransactionTemplate(new JdbcTransactionManager(dataSource)).executeWithoutResult(status -> {
            if (jdbc.queryForObject("select count(*) from cils_account where username = ?", Integer.class, "demo") != 0
                    || clients.findByClientId("local-client") != null) {
                throw new IllegalStateException("Development fixtures already exist; no credentials were overwritten");
            }
            jdbc.update("insert into cils_account(id, username, password_hash, enabled) values (?, ?, ?, ?)",
                UUID.randomUUID(), "demo", encoder.encode(password), true);
            clients.save(RegisteredClient.withId(UUID.randomUUID().toString()).clientId("local-client")
                .clientSecret(encoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/cils")
                .scope("openid").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false)
                    .accessTokenTimeToLive(java.time.Duration.ofMinutes(5))
                    .refreshTokenTimeToLive(java.time.Duration.ofHours(1)).build()).build());
        });
        System.out.println("Development fixtures created: demo / local-client. Credentials were not printed.");
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required environment variable: " + name);
        return value;
    }
}