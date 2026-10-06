package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AdminUserServiceTest {
    //dependencies
    private UserRepository userRepository;
    //services
    private AuditLogService auditLogService;
    private AdminUserService adminUserService;

    @BeforeEach
    public void setUp() {

        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);

        adminUserService = new AdminUserService(userRepository, auditLogService);
    }

    //test 4: Admin soft deletion/deactivate user
    @Test
    @DisplayName("When admin deactivates user then status becomes inactive")
    public void whenAdminDeactivatesUserThenStatusBecomesInactive() {
        //create active user
        User user = new User();
        user.setId(1L);
        user.setEmailAddress("user@test.com");
        user.setStatus(UserStatus.ACTIVE);

        //mock repository response
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        //deactivate user
        adminUserService.changeUserStatus("admin@test.com", 1L, UserStatus.INACTIVE);

        //verify status changed to inactive
        assertEquals(UserStatus.INACTIVE, user.getStatus());

    }
}
