package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import com.ga.investmentportfolio.DTO.Response.AdminUserResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import com.ga.investmentportfolio.Service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ga.investmentportfolio.Enums.UserStatus;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin User Management")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {
    private final AdminUserService adminUserService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List users without sensitive account fields")
    public Page<AdminUserResponse> listUsers(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return adminUserService.listUsers(page, size);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change user account status")
    public ResponseEntity<MessageResponse> deactivateUser(@PathVariable Long id,
                                                          @RequestParam UserStatus status,
                                                          Authentication authentication) {

        adminUserService.changeUserStatus(authentication.getName(), id, status);

        return ResponseEntity.ok(new MessageResponse("User status changed to " + status + " successfully."));
    }

}
