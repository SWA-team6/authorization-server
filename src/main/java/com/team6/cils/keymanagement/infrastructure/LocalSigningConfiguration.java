package com.team6.cils.keymanagement.infrastructure;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalSigningConfiguration {
    @Bean
    JWKSource<SecurityContext> jwkSource() throws Exception {
        //RSA 개인키, 공개키 생성(JCA를 사용)
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048); //키 길이 2048 비트
        var pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()) // 공개키를 활용하여 JWK 생성시작
            .privateKey((RSAPrivateKey) pair.getPrivate()) // 개인키도 내부에 함께 등록, 이를 통해 JWT 서명 생성 가능
                //해당 키를 식별할 고유 아이디 설정
            .keyID(UUID.randomUUID().toString()).build();
        // 하나 이상의 JWK를 포함하는 집합을 반환
        return new ImmutableJWKSet<>(new JWKSet(key)); //ImmutableJWKSet: 변경되지 않는 JWK 집합을 제공
    }
}
