package com.ga.notificationservice;

import com.ga.notificationservice.Event.WalletCompletedConsumer;
import com.ga.notificationservice.Service.EmailService;
import com.ga.notificationservice.Service.NotificationService;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(WalletKafkaTests.Config.class)
@EmbeddedKafka(partitions = 1, topics = "investment.wallet-completed")
@DirtiesContext
class WalletKafkaTests {
    @Configuration
    @EnableKafka
    static class Config {
        @Bean NotificationService notifications() { return mock(NotificationService.class); }
        @Bean EmailService emails() { return mock(EmailService.class); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean CountDownLatch consumed() { return new CountDownLatch(4); }
        @Bean WalletCompletedConsumer consumer(NotificationService notifications, EmailService emails, JdbcTemplate jdbc) {
            return new WalletCompletedConsumer(notifications, emails, JsonMapper.builder().build(), jdbc);
        }
        @Bean ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(EmbeddedKafkaBroker broker, CountDownLatch consumed) {
            var properties = KafkaTestUtils.consumerProps("wallet-notification-test", "false", broker);
            properties.put("auto.offset.reset", "earliest");
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer()));
            factory.setRecordInterceptor(new RecordInterceptor<>() {
                @Override public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                    return record;
                }
                @Override public void success(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                    consumed.countDown();
                }
            });
            return factory;
        }
    }

    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private CountDownLatch consumed;
    @Autowired private EmailService emails;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void kafkaDeliversBothOperationTypesAndRedeliveryIsDeduplicated() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L))).thenReturn("investor@example.com");
        var producer = new DefaultKafkaProducerFactory<String, String>(KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer());
        try {
            KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producer);
            for (String type : new String[]{"DEPOSIT", "WITHDRAWAL"}) {
                String message = """
                        {"userId":42,"reference":"%s","operationType":"%s","amount":10.00,"status":"COMPLETED"}
                        """.formatted(UUID.randomUUID(), type);
                kafka.send("investment.wallet-completed", message).get(10, TimeUnit.SECONDS);
                kafka.send("investment.wallet-completed", message).get(10, TimeUnit.SECONDS);
            }
            assertTrue(consumed.await(15, TimeUnit.SECONDS));
            for (String type : new String[]{"DEPOSIT", "WITHDRAWAL"}) {
                verify(emails, times(1)).sendWalletEmail(eq("investor@example.com"), eq(type), any(), any());
            }
            verify(notifications, times(2)).sendNotification(eq("investor@example.com"), contains("USD 10.00"));
        } finally { producer.destroy(); }
    }
}
