package com.team6.cils.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class SecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain unavailableLogout(HttpSecurity http) throws Exception {
        http.securityMatcher("/connect/logout", "/logout")
            .addFilterBefore((request, response, chain) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(404),
                org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .authorizeHttpRequests(requests -> requests.anyRequest().denyAll());
        return http.build();
    }


    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.oidc(oidc -> oidc.providerConfigurationEndpoint(metadata -> metadata
                .providerConfigurationCustomizer(builder -> builder.claims(claims -> claims.remove("end_session_endpoint")))));
        })
            .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                    new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                    org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher("/userinfo"))
                .defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            // CILS logout requires session-grant coordination and durable delivery.
            .logout(logout -> logout.disable());
        return http.build();
    }
}




