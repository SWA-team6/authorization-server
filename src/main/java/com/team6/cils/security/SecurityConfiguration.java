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
@Profile("local") //local profile이 활성화 된 경우만 해당 설정 적용, 비활성화의 경우 "./RuntimeConfiguration.java" 참조
public class SecurityConfiguration {

    // 로그아웃 엔드포인트 차단. (spring secirity에서 일반적으로 제공하는 /logout 엔드포인트 진입 전에 Authorization 서버에서 가로챈다.)
    @Bean
    @Order(0)
    SecurityFilterChain unavailableLogout(HttpSecurity http) throws Exception {
        http.securityMatcher("/connect/logout", "/logout") //해당 필터체인이 처리할 엔드포인트 지정
            .addFilterBefore((request, response, chain) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(404), //람다식으로 정의
                org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .authorizeHttpRequests(requests -> requests.anyRequest().denyAll()); //의미 확인 필요
        return http.build();
    }


    // OAuth2 인증 서버 기능 설정
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        // Spring Authorization Server가 제공하는 OAuth2 인증 서버 관련 보안 기능을 구현하는 DSL
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher()); //해당 필터체인이 처리할 엔드포인트를 프레임워크에서 제공하는 Authorization server 엔드포인트로 지정
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

    // 모든 HTTP 요청을 처리하는 체인
    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http) throws Exception {
        // http.securityMatcher()가 없다!! => 모든 요청에 응답한다. 앞선 두 필터체인에서 선택되지 않은 경우 여기서 처리
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/error").permitAll() //error 경로는 로그인하지 않은 사용자도 접근할 수 있도록 허용, spring boot에서 일반적으로 /error를 기본 오류 처리 경로로 사용 : 잘못된 경로로 접근하여 오류가 발생했을 때, 불필요한 인증 요구를 방지
                .anyRequest().authenticated()) //error 경로가 아닌 경우 모든 요청은 인증된 사용자만 접근 가능.
            .formLogin(Customizer.withDefaults()) // spring security의 기본 폼 로그인 설정
            // CILS logout requires session-grant coordination and durable delivery.
            .logout(logout -> logout.disable()); //spring security의 로그아웃을 비활성화(why? spring security에서는 세션 무효화 만을 수행함)
        return http.build();
    }
}




