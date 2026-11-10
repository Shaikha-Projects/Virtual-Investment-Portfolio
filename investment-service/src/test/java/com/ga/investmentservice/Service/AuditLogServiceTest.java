package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.AuditAction;
import com.ga.investmentservice.Model.AuditLog;
import com.ga.investmentservice.Repository.AuditLogRepository;
import com.ga.investmentservice.Security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AuditLogService auditLogService = new AuditLogService(auditLogRepository,
            mock(com.ga.investmentservice.Repository.PortfolioRepository.class));

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        AuthenticatedUser user = new AuthenticatedUser(42L, "investor@example.com", "USER");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @ParameterizedTest
    @EnumSource(value = AuditAction.class, names = {"BUY_ASSET", "SELL_ASSET", "CREATE_ASSET",
            "UPDATE_ASSET", "CHANGE_ASSET_STATUS", "PLACE_LIMIT_ORDER", "CANCEL_LIMIT_ORDER", "EXECUTE_LIMIT_ORDER"})
    void savesInvestmentActionsWithAuthenticatedEmailAndUnchangedDetails(AuditAction action) {
        authenticate();
        String details = "Original details: 3.50 shares of ABC";
        auditLogService.log("investor@example.com", action, details);

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertEquals("investor@example.com", saved.getValue().getUserEmail());
        assertEquals(action, saved.getValue().getAction());
        assertEquals(details, saved.getValue().getDetails());
        verifyNoMoreInteractions(auditLogRepository);
    }

    @Test
    void rejectsSpoofedAuditEmail() {
        authenticate();
        assertThrows(AccessDeniedException.class,
                () -> auditLogService.log("victim@example.com", AuditAction.BUY_ASSET, "Bought shares"));
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void rejectsMissingAndUnverifiedIdentity() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> auditLogService.log("investor@example.com", AuditAction.BUY_ASSET, "Bought shares"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("investor@example.com", null, List.of()));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> auditLogService.log("investor@example.com", AuditAction.BUY_ASSET, "Bought shares"));
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void preservesAuditHistoryPaginationAndOrdering() {
        PageRequest pageable = PageRequest.of(2, 10);
        Page<AuditLog> logs = Page.empty(pageable);
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(logs);
        assertSame(logs, auditLogService.getAllLogs(2, 10));
        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(pageable);
    }
}
