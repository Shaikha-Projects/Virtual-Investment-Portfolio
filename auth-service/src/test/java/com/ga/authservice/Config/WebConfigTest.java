package com.ga.authservice.Config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebConfigTest {
    private final ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);
    private final ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);

    @ParameterizedTest
    @ValueSource(strings = {"", "auth-service"})
    void developmentWorkingDirectoriesResolveTheSameRepositoryUploads(String directory) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path project = current.getFileName().toString().equals("auth-service") ? current.getParent() : current;
        String previous = System.getProperty("user.dir");
        when(registry.addResourceHandler("/uploads/**")).thenReturn(registration);
        try {
            System.setProperty("user.dir", project.resolve(directory).toString());
            new WebConfig().addResourceHandlers(registry);
            verify(registration).addResourceLocations(project.resolve("uploads").toUri().toString(), "file:uploads/");
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    @Test
    void absoluteConfigurationIsUsedWithoutLosingCurrentUploadStorage() {
        Path uploads = Path.of(System.getProperty("java.io.tmpdir"), "auth-config-test-uploads").toAbsolutePath();
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "uploadsDirectory", uploads.toString());
        when(registry.addResourceHandler("/uploads/**")).thenReturn(registration);
        config.addResourceHandlers(registry);
        String location = uploads.toUri().toString();
        if (!location.endsWith("/")) location += "/";
        verify(registration).addResourceLocations(location, "file:uploads/");
    }

    @Test
    void relativeOverrideIsRejectedInsteadOfDependingOnLaunchDirectory() {
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "uploadsDirectory", "../uploads");
        assertThrows(IllegalArgumentException.class, () -> config.addResourceHandlers(registry));
        verifyNoInteractions(registry);
    }
}
