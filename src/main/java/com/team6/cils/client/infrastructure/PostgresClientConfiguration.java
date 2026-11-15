package com.team6.cils.client.infrastructure;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.*;
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class PostgresClientConfiguration {
    @Bean
    RegisteredClientRepository registeredClients(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }
}