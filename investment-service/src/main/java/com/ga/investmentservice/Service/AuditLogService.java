package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.AuditAction;
import com.ga.investmentservice.Model.AuditLog;
import com.ga.investmentservice.Repository.AuditLogRepository;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Model.InvestmentOrder;
import com.ga.investmentservice.Enums.OrderStatus;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.ga.investmentservice.Security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;
    private final PortfolioRepository portfolioRepository;

    public void log(String userEmail, AuditAction action, String details) {
        //verify the audit actor using the identity established by Investment security
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        if (!authentication.getName().equals(userEmail)) {
            throw new AccessDeniedException("Audit user must match the authenticated user");
        }

        //create log object
        AuditLog auditLog = new AuditLog();

        //set the fields
        auditLog.setUserEmail(userEmail);
        auditLog.setAction(action);
        auditLog.setDetails(details);

        //save audit log
        auditLogRepository.save(auditLog);
    }

    //background orders have no HTTP identity; use their stored owner and a fixed audit action
    @Transactional(propagation = Propagation.MANDATORY)
    void logOrderExecution(InvestmentOrder order, String details) {
        if (order.getId() == null || order.getStatus() != OrderStatus.FILLED) {
            throw new IllegalArgumentException("Only filled orders can be audited as executed");
        }
        String userEmail = portfolioRepository.findUserEmailByUserId(order.getPortfolio().getUserId())
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        AuditLog auditLog = new AuditLog();
        auditLog.setUserEmail(userEmail);
        auditLog.setAction(AuditAction.EXECUTE_LIMIT_ORDER);
        auditLog.setDetails(details);
        auditLogRepository.save(auditLog);
    }

    //method to get all audit logs
    public Page<AuditLog> getAllLogs(int page, int size){
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
