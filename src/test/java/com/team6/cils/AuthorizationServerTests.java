package com.team6.cils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "cils.local.password=synthetic-test-password",
    "spring.security.oauth2.authorizationserver.client.local.registration.client-secret={noop}synthetic-client-secret"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AuthorizationServerTests {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.session.data.redis.RedisSessionRepository sessions;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    private static final String NAMESPACE = "cils:test:" + java.util.UUID.randomUUID();

    @org.springframework.test.context.DynamicPropertySource
    static void isolatedSessions(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.session.redis.namespace", () -> NAMESPACE);
    }

    @Test
    void loginPersistsSecurityContextAndIdleExpiryInRedis() throws Exception {
        var cookie = mvc.perform(formLogin().user("demo").password("synthetic-test-password"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getCookie("SESSION");
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        org.junit.jupiter.api.Assertions.assertTrue(cookie.isHttpOnly());
        String id = new String(java.util.Base64.getDecoder().decode(cookie.getValue()),
            java.nio.charset.StandardCharsets.UTF_8);
        Session stored = sessions.findById(id);
        org.junit.jupiter.api.Assertions.assertNotNull(stored);
        org.springframework.security.core.context.SecurityContext context =
            stored.getAttribute("SPRING_SECURITY_CONTEXT");
        org.junit.jupiter.api.Assertions.assertEquals("demo", context.getAuthentication().getName());
        org.junit.jupiter.api.Assertions.assertEquals(java.time.Duration.ofMinutes(30), stored.getMaxInactiveInterval());
        Long ttl = redis.getExpire(NAMESPACE + ":sessions:" + id);
        org.junit.jupiter.api.Assertions.assertTrue(ttl != null && ttl > 0 && ttl <= 1800);
        mvc.perform(get("/").cookie(cookie))
            .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername("demo"));
        mvc.perform(get("/").accept(org.springframework.http.MediaType.TEXT_HTML))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void codeExchangeUserInfoRefreshAndReplay() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var session = mvc.perform(
                formLogin().user("demo").password("synthetic-test-password"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getCookie("SESSION");
        var response = mvc.perform(get("/oauth2/authorize")
                .cookie(session)
                .queryParam("client_id", "local-client")
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/cils")
                .queryParam("scope", "openid")
                .queryParam("state", "synthetic-state").queryParam("nonce", "synthetic-nonce")
                .queryParam("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        var query = org.springframework.web.util.UriComponentsBuilder
            .fromUriString(response.getRedirectedUrl()).build().getQueryParams();
        org.junit.jupiter.api.Assertions.assertEquals("synthetic-state", query.getFirst("state"));
        String code = query.getFirst("code");
        org.junit.jupiter.api.Assertions.assertNotNull(code);
        var tokenRequest = post("/oauth2/token")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("local-client", "synthetic-client-secret"))
            .param("grant_type", "authorization_code").param("code", code)
            .param("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/cils")
            .param("code_verifier", verifier);
        String body = mvc.perform(tokenRequest).andExpect(status().isOk())
            .andExpect(jsonPath("$.id_token").exists())
            .andExpect(jsonPath("$.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();
        String access = com.jayway.jsonpath.JsonPath.read(body, "$.access_token");
        String refresh = com.jayway.jsonpath.JsonPath.read(body, "$.refresh_token");
        mvc.perform(get("/userinfo").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("demo"));
        mvc.perform(post("/oauth2/token")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("local-client", "synthetic-client-secret"))
                .param("grant_type", "refresh_token").param("refresh_token", refresh))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.not(refresh)));
        mvc.perform(tokenRequest).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void discoveryPublishesIssuerAndCodeFlow() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost:9000"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost:9000/oauth2/token"))
            .andExpect(jsonPath("$.end_session_endpoint").doesNotExist());
    }

    @Test
    void jwksExposesOnlyPublicKey() throws Exception {
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
            .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
            .andExpect(jsonPath("$.keys[0].n").exists())
            .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void loginAcceptsConfiguredUserAndRejectsWrongPassword() throws Exception {
        mvc.perform(formLogin().user("demo").password("synthetic-test-password"))
            .andExpect(redirectedUrl("/"));
        mvc.perform(formLogin().user("demo").password("wrong"))
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void loginRequiresCsrf() throws Exception {
        mvc.perform(post("/login").param("username", "demo").param("password", "synthetic-test-password"))
            .andExpect(status().isForbidden());
    }

    @Test
    void userInfoRequiresBearerToken() throws Exception {
        mvc.perform(get("/userinfo")).andExpect(status().isUnauthorized());
    }

    @Test
    void incompleteLogoutIsNotExposed() throws Exception {
        mvc.perform(get("/connect/logout")).andExpect(status().isNotFound());
        mvc.perform(post("/logout")).andExpect(status().isNotFound());
    }
}



