package com.team6.cils.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class PostgresIdentityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcOperations jdbc) {
        return username -> {
            var accounts = jdbc.query("""
                select username, password_hash, enabled from cils_account where username = ?
                """, (row, index) -> User.withUsername(row.getString("username"))
                    .password(row.getString("password_hash"))
                    .disabled(!row.getBoolean("enabled"))
                    .authorities(java.util.List.of()).build(), username);
            if (accounts.isEmpty()) {
                throw new UsernameNotFoundException("Invalid credentials");
            }
            return accounts.getFirst();
        };
    }
}
