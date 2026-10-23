package com.example.financial;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ApplicationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:17.7");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsWithRealPostgreSql() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version()", String.class))
                .startsWith("PostgreSQL");
    }
}
