package com.ga.authservice.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer{
    @Value("${auth.uploads-directory:}")
    private String uploadsDirectory;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path uploadPath;
        if (uploadsDirectory == null || uploadsDirectory.isBlank()) {
            //support both repository-root and auth-service working directories
            Path projectDirectory = workingDirectory.getFileName().toString().equals("auth-service")
                    ? workingDirectory.getParent() : workingDirectory;
            uploadPath = projectDirectory.resolve("uploads");
        } else {
            uploadPath = Path.of(uploadsDirectory);
            if (!uploadPath.isAbsolute()) {
                throw new IllegalArgumentException("Authentication uploads directory must be absolute");
            }
        }

        String uploadLocation = uploadPath.normalize().toUri().toString();
        if (!uploadLocation.endsWith("/")) {
            uploadLocation += "/";
        }

        registry
                .addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation, "file:uploads/");
    }
}
