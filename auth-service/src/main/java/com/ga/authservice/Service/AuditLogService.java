package com.ga.authservice.Service;

import com.ga.authservice.Enums.AuditAction;
import com.ga.authservice.Model.AuditLog;
import com.ga.authservice.Repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    //method to get all audit logs
    public Page<AuditLog> getAllLogs(int page, int size){
        Pageable pageable = PageRequest.of(page, size);
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
