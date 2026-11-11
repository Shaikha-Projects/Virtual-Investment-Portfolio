package com.ga.authservice;

import com.ga.authservice.Model.User;
import com.ga.authservice.Event.OutboxPublisher;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

//Opt in only with the real PostgreSQL credentials available. Kafka and mail remain mocked.
@EnabledIfEnvironmentVariable(named = "AUTH_POSTGRES_TESTS", matches = "true")
@SpringBootTest(properties = {
        "jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "internal.service.key=synthetic-test-service-key",
        "spring.mail.username=test",
        "spring.mail.password=test",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.read-only=true",
        "spring.datasource.hikari.connection-init-sql=SET default_transaction_read_only = on",
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
})
@MockitoBean(types = {KafkaTemplate.class, JavaMailSender.class, OutboxPublisher.class})
class AuthServicePostgresTests {
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @Test
    void postgresSchemaAndJpaStartupAreCompatibleWithoutWritingData() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.isReadOnly());
        }
        assertNotNull(entityManagerFactory.getMetamodel().entity(User.class));
        var entityManager = entityManagerFactory.createEntityManager();
        try {
            assertNotNull(entityManager.createQuery("select count(u) from User u", Long.class).getSingleResult());
        } finally {
            entityManager.close();
        }
    }
}
