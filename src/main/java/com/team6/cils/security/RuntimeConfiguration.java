package com.team6.cils.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
    @Bean
    @Profile("!local")
    Object requireConfiguredStorage() {
        throw new IllegalStateException(
            "Only the local bootstrap is implemented. Enable the local profile explicitly.");
    }
}
