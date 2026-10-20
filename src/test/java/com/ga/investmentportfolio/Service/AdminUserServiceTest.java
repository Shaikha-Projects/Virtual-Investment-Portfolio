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
    @Test
    public void adminCannotDeactivateOwnAccount() {
        User user = new User();
        user.setId(1L);
        user.setEmailAddress("admin@test.com");
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ga.investmentportfolio.Exception.BusinessRuleException.class,
                () -> adminUserService.changeUserStatus("ADMIN@test.com", 1L, UserStatus.INACTIVE));
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
    }

    @Test
    public void listUsersCapsPageSizeAndReturnsSafeProjection() {
        User user = new User(); user.setId(1L); user.setEmailAddress("user@test.com");
        user.setPassword("must-not-be-returned"); user.setRole(com.ga.investmentportfolio.Enums.Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        when(userRepository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(user)));
        var page = adminUserService.listUsers(-1, 1000);
        var pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(userRepository).findAll(pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals("user@test.com", page.getContent().get(0).emailAddress());
    }
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
