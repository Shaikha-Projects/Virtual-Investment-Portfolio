package com.ga.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ga.authservice.Repository.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "internal.service.key=synthetic-test-service-key",
        "spring.mail.username=test",
        "spring.mail.password=test",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
})
@MockitoBean(types = {UserRepository.class, AuditLogRepository.class, EmailVerificationTokenRepository.class,
        PasswordResetTokenRepository.class, OutboxEventRepository.class, KafkaTemplate.class, JavaMailSender.class})
class AuthServiceApplicationTests {

	@Test
	void mockedContextLoadsWithoutExternalServices() {
	}

	@org.springframework.beans.factory.annotation.Autowired
	private org.springframework.web.context.WebApplicationContext webApplicationContext;

	@org.springframework.boot.test.web.server.LocalServerPort
	private int port;

	@Test
	void uploadedPicturesUseTheOriginalFilesystemResourceMapping() throws Exception {
		var mapping = webApplicationContext.getBean("resourceHandlerMapping",
				org.springframework.web.servlet.handler.SimpleUrlHandlerMapping.class);
		var handler = (org.springframework.web.servlet.resource.ResourceHttpRequestHandler)
				mapping.getUrlMap().get("/uploads/**");
		org.junit.jupiter.api.Assertions.assertNotNull(handler);
		org.junit.jupiter.api.Assertions.assertEquals("file:uploads/", handler.getLocations().get(1).getURI().toString());
		java.nio.file.Path uploads = java.nio.file.Path.of(handler.getLocations().get(0).getURI());
		org.junit.jupiter.api.Assertions.assertTrue(uploads.isAbsolute());
		org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isDirectory(uploads.resolve("profile-pictures")));
	}

	@Test
	void existingProfileImageIsRetrievedOverHttpWithoutChangingIt() throws Exception {
		var mapping = webApplicationContext.getBean("resourceHandlerMapping",
				org.springframework.web.servlet.handler.SimpleUrlHandlerMapping.class);
		var handler = (org.springframework.web.servlet.resource.ResourceHttpRequestHandler)
				mapping.getUrlMap().get("/uploads/**");
		java.nio.file.Path uploads = java.nio.file.Path.of(handler.getLocations().get(0).getURI());
		try (var files = java.nio.file.Files.list(uploads.resolve("profile-pictures"))) {
			var image = files.filter(java.nio.file.Files::isRegularFile)
					.filter(file -> file.getFileName().toString().endsWith(".png")).findFirst().orElseThrow();
			byte[] original = java.nio.file.Files.readAllBytes(image);
			var uri = new java.net.URI("http", null, "localhost", port,
					"/uploads/profile-pictures/" + image.getFileName(), null, null);
			var response = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
					.send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build(),
							java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode());
			org.junit.jupiter.api.Assertions.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
			org.junit.jupiter.api.Assertions.assertArrayEquals(original, response.body());
			org.junit.jupiter.api.Assertions.assertArrayEquals(original, java.nio.file.Files.readAllBytes(image));
		}
	}

}
