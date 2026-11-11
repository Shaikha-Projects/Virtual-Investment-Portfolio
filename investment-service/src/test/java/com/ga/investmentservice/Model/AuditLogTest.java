package com.ga.investmentservice.Model;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    @Test
    void preservesExistingAuditTableMapping() throws Exception {
        assertEquals("audit_logs", AuditLog.class.getAnnotation(Table.class).name());
        assertEquals(GenerationType.IDENTITY,
                AuditLog.class.getDeclaredField("id").getAnnotation(GeneratedValue.class).strategy());
        assertEquals(EnumType.STRING,
                AuditLog.class.getDeclaredField("action").getAnnotation(Enumerated.class).value());
        for (String field : new String[]{"userEmail", "action", "createdAt"}) {
            assertFalse(AuditLog.class.getDeclaredField(field).getAnnotation(Column.class).nullable());
        }
        assertNotNull(AuditLog.class.getDeclaredMethod("onCreate").getAnnotation(PrePersist.class));
    }

    @Test
    void assignsCreationTimeBeforePersistence() {
        AuditLog auditLog = new AuditLog();
        LocalDateTime before = LocalDateTime.now();
        assertNull(auditLog.getCreatedAt());
        auditLog.onCreate();
        assertFalse(auditLog.getCreatedAt().isBefore(before));
        assertFalse(auditLog.getCreatedAt().isAfter(LocalDateTime.now()));
    }
}
