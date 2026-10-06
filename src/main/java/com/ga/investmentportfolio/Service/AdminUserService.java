package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.AuditAction;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public void changeUserStatus(String adminEmail, Long userId, UserStatus newStatus) {

        //find user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InformationNotFoundException("User not found with id: " + userId));

        //if user already has this status
        if (user.getStatus() == newStatus) {
            throw new BusinessRuleException("User is already " + newStatus.toString().toLowerCase() + ".");
        }

        //only allow two action: ACTIVE or INACTIVE
        if (newStatus != UserStatus.ACTIVE && newStatus != UserStatus.INACTIVE) {
            throw new BusinessRuleException("Admin can only set user status to ACTIVE or INACTIVE.");
        }

        //change user status
        user.setStatus(newStatus);
        userRepository.save(user);

        // add aduit log
        if (newStatus == UserStatus.ACTIVE) {
            //activate log
            auditLogService.log(adminEmail, AuditAction.ACTIVATE_USER, "Activated user " + user.getEmailAddress());
        } else {
            //deactivate log
            auditLogService.log(adminEmail, AuditAction.DEACTIVATE_USER, "Deactivated user " + user.getEmailAddress());
        }

    }
}
