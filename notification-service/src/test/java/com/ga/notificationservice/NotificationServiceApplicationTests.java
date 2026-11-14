package com.ga.notificationservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.mail.username=notifications@example.com",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {
        org.springframework.jdbc.core.JdbcTemplate.class, org.springframework.mail.javamail.JavaMailSender.class
})
class NotificationServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
