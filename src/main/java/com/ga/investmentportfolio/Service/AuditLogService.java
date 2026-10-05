package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.AuditAction;
import com.ga.investmentportfolio.Model.AuditLog;
import com.ga.investmentportfolio.Repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;

    public void log(String userEmail, AuditAction action, String details) {
        //create log object
        AuditLog auditLog = new AuditLog();

        //set the fields
        auditLog.setUserEmail(userEmail);
        auditLog.setAction(action);
        auditLog.setDetails(details);

        //save audit log
        auditLogRepository.save(auditLog);
    }
}
