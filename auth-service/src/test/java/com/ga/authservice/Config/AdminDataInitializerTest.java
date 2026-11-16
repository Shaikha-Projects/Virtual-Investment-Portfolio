package com.ga.authservice.Config;

import com.ga.authservice.Enums.Role;
import com.ga.authservice.Enums.UserStatus;
import com.ga.authservice.Model.User;
import com.ga.authservice.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AdminDataInitializerTest {
    private static final String EMAIL = "bootstrap@example.test";
    private static final String PASSWORD = " synthetic-test-password ";
    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder());

    @Test
    void firstStartupCreatesAnActiveAdminWithRequiredFieldsAndHashedPassword(CapturedOutput output) {
        new AdminDataInitializer(repository, encoder, EMAIL, PASSWORD).run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
        User admin = saved.getValue();
        assertEquals(EMAIL, admin.getEmailAddress());
        assertEquals(Role.ADMIN, admin.getRole());
        assertEquals(UserStatus.ACTIVE, admin.getStatus());
        assertEquals("System", admin.getFirstName());
        assertEquals("Administrator", admin.getLastName());
        assertTrue(admin.getPhoneNumber().matches("\\d{8}"));
        assertNull(admin.getId()); // Assigned by PostgreSQL, never hardcoded.
        verify(encoder).encode(PASSWORD);
        assertNotEquals(PASSWORD, admin.getPassword());
        assertTrue(encoder.matches(PASSWORD, admin.getPassword()));
        assertTrue(output.getOut().contains("initialized successfully"));
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains(admin.getPassword()));
    }

    @Test
    void repeatedStartupDoesNotCreateAnotherAccountOrEncodeAgain() {
        when(repository.existsByEmailAddress(EMAIL)).thenReturn(false, true);
        AdminDataInitializer initializer = new AdminDataInitializer(repository, encoder, EMAIL, PASSWORD);
        initializer.run();
        initializer.run();

        verify(repository, times(2)).existsByEmailAddress(EMAIL);
        verify(repository, times(1)).save(any(User.class));
        verify(encoder, times(1)).encode(PASSWORD);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void existingAccountIsNeverModifiedEvenIfItIsNotAnActiveAdmin(Role role, CapturedOutput output) {
        User existing = new User();
        existing.setEmailAddress(EMAIL);
        existing.setRole(role);
        existing.setStatus(UserStatus.INACTIVE);
        existing.setFirstName("Existing");
        existing.setPassword("existing-hash");
        when(repository.existsByEmailAddress(EMAIL)).thenReturn(true);

        new AdminDataInitializer(repository, encoder, EMAIL, PASSWORD).run();

        verify(repository).existsByEmailAddress(EMAIL);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(encoder);
        assertEquals(role, existing.getRole());
        assertEquals(UserStatus.INACTIVE, existing.getStatus());
        assertEquals("existing-hash", existing.getPassword());
        assertEquals("Existing", existing.getFirstName());
        assertTrue(output.getAll().contains("account left unchanged"));
        assertFalse(output.getAll().contains(PASSWORD));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL|secret", "''|secret", "'   '|secret", "admin@example.test|NULL",
            "admin@example.test|''", "admin@example.test|'   '", "NULL|NULL"},
            delimiter = '|', nullValues = "NULL")
    void missingOrBlankConfigurationSkipsAllDatabaseAndEncoderAccess(String email, String password,
                                                                    CapturedOutput output) {
        new AdminDataInitializer(repository, encoder, email, password).run();

        verifyNoInteractions(repository, encoder);
        assertTrue(output.getAll().contains("ADMIN_EMAIL or ADMIN_PASSWORD is missing or blank"));
        if (password != null && !password.isBlank()) assertFalse(output.getAll().contains(password));
    }
}
