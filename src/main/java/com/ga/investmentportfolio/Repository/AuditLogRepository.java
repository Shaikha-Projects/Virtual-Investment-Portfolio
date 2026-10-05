package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
