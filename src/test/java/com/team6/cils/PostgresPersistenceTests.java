package com.team6.cils;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("postgres")
@AutoConfigureMockMvc(printOnlyOnFailure = false, print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CILS_TEST_DB_URL", matches = ".+")
class PostgresPersistenceTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegisteredClientRepository clients;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired PasswordEncoder encoder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("CILS_TEST_DB_URL");
        String user = System.getenv("CILS_DB_USERNAME");
        String password = System.getenv("CILS_DB_PASSWORD");
        String schema = "cils_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        var key = new RSAKeyGenerator(2048).keyID("synthetic-test-key").generate();
        var path = Files.createTempFile("cils-test-key-", ".json");
        Files.writeString(path, key.toJSONString());
        path.toFile().deleteOnExit();
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("cils.signing.jwk-file", path::toString);
        properties.add("spring.security.oauth2.authorizationserver.issuer", () -> "http://localhost:9000");
        System.out.println("Retained synthetic test schema: " + schema);
    }

    @BeforeAll
    void fixtures() {
        jdbc.update("insert into cils_account(id, username, password_hash, enabled) values (?, ?, ?, ?)",
            UUID.randomUUID(), "demo", encoder.encode("synthetic-test-password"), true);
        jdbc.update("insert into cils_account(id, username, password_hash, enabled) values (?, ?, ?, ?)",
            UUID.randomUUID(), "disabled", encoder.encode("synthetic-test-password"), false);
        clients.save(RegisteredClient.withId("synthetic-client-id").clientId("local-client")
            .clientSecret(encoder.encode("synthetic-client-secret"))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("http://127.0.0.1:8080/login/oauth2/code/cils")
            .scope("openid").scope("profile")
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build()).build());
    }

    @Test
    void storedAccountsAndConsent() throws Exception {
        mvc.perform(formLogin().user("demo").password("synthetic-test-password")).andExpect(redirectedUrl("/"));
        mvc.perform(formLogin().user("demo").password("wrong")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(formLogin().user("disabled").password("synthetic-test-password")).andExpect(redirectedUrl("/login?error"));
        consents.save(OAuth2AuthorizationConsent.withId("synthetic-client-id", "demo").scope("profile").build());
        var freshClients = new JdbcRegisteredClientRepository(jdbc);
        var freshConsents = new JdbcOAuth2AuthorizationConsentService(jdbc, freshClients);
        assertNotNull(freshClients.findByClientId("local-client"));
        assertTrue(freshConsents.findById("synthetic-client-id", "demo").getScopes().contains("profile"));
    }

    @Test
    void persistedCodeExchangeAndRefresh() throws Exception {
        var session = (org.springframework.mock.web.MockHttpSession) mvc.perform(
            formLogin().user("demo").password("synthetic-test-password"))
            .andReturn().getRequest().getSession(false);
        var response = mvc.perform(get("/oauth2/authorize").session(session)
            .queryParam("client_id", "local-client").queryParam("response_type", "code")
            .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/cils")
            .queryParam("scope", "openid").queryParam("state", "synthetic-state").queryParam("nonce", "synthetic-nonce")
            .queryParam("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
            .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String code = org.springframework.web.util.UriComponentsBuilder.fromUriString(response.getRedirectedUrl())
            .build().getQueryParams().getFirst("code");
        assertNotNull(code);
        var fresh = new JdbcOAuth2AuthorizationService(jdbc, new JdbcRegisteredClientRepository(jdbc));
        assertNotNull(fresh.findByToken(code, new OAuth2TokenType("code")));
        var request = post("/oauth2/token").with(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .httpBasic("local-client", "synthetic-client-secret"))
            .param("grant_type", "authorization_code").param("code", code)
            .param("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/cils")
            .param("code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        String body = mvc.perform(request).andExpect(status().isOk())
            .andExpect(jsonPath("$.id_token").exists()).andReturn().getResponse().getContentAsString();
        String access = com.jayway.jsonpath.JsonPath.read(body, "$.access_token");
        String refresh = com.jayway.jsonpath.JsonPath.read(body, "$.refresh_token");
        assertNotNull(fresh.findByToken(refresh, OAuth2TokenType.REFRESH_TOKEN));
        mvc.perform(get("/userinfo").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("demo"));
        mvc.perform(post("/oauth2/token").with(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .httpBasic("local-client", "synthetic-client-secret"))
            .param("grant_type", "refresh_token").param("refresh_token", refresh))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.not(refresh)));
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }
}