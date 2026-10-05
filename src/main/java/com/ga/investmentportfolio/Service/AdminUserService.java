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

    public void deactivateUser(String adminEmail, Long userId) {

        //find user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InformationNotFoundException("User not found with id: " + userId));

        //if user is already inactive
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new BusinessRuleException("User is already inactive.");
        }

        //deactivate user, save user
        user.setStatus(UserStatus.INACTIVE);
        userRepository.save(user);

        // add log
        auditLogService.log(adminEmail, AuditAction.DEACTIVATE_USER, "Deactivated user " + user.getEmailAddress());

    }
}
