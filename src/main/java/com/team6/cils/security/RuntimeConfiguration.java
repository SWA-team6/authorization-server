package com.team6.cils.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
    @Bean
    @Profile("!local & !postgres")
    Object requireConfiguredStorage() {
        throw new IllegalStateException(
            "Enable either local or postgres with its required configuration.");
    }

    @Bean
    @Profile("local & postgres")
    Object rejectConflictingProfiles() {
        throw new IllegalStateException("local and postgres profiles must not be combined");
    }
}
