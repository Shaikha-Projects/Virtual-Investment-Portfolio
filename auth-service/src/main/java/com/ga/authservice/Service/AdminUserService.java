package com.ga.authservice.Service;

import com.ga.authservice.DTO.Response.AdminUserResponse;
import com.ga.authservice.Enums.AuditAction;
import com.ga.authservice.Enums.UserStatus;
import com.ga.authservice.Exception.BusinessRuleException;
import com.ga.authservice.Exception.InformationNotFoundException;
import com.ga.authservice.Model.User;
import com.ga.authservice.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page),
                Math.max(1, Math.min(size, 100)), Sort.by("id"));
        return userRepository.findAll(pageable).map(user ->
                new AdminUserResponse(user.getId(), user.getFirstName(),
                        user.getLastName(), user.getEmailAddress(), user.getRole(), user.getStatus()));
    }

    public void changeUserStatus(String adminEmail, Long userId, UserStatus newStatus) {

        //find user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InformationNotFoundException("User not found with id: " + userId));

        //prevent admin from deactivating his account
        if (user.getEmailAddress().equalsIgnoreCase(adminEmail) && newStatus == UserStatus.INACTIVE) {
            throw new BusinessRuleException("Admins cannot deactivate their own accounts.");
        }


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
