package com.team6.cils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@AutoConfigureMockMvc
class AuthorizationServerTests {
    @Autowired MockMvc mvc;

    @Test
    void codeExchangeUserInfoRefreshAndReplay() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var session = (org.springframework.mock.web.MockHttpSession) mvc.perform(
                formLogin().user("demo").password("synthetic-test-password"))
            .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
        var response = mvc.perform(get("/oauth2/authorize")
                .session(session)
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



