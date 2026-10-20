package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Response.AdminUserResponse;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Security.*;
import com.ga.investmentportfolio.Service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AdminUserController.class, AdminAssetController.class, AuthController.class})
@Import(SecurityConfiguration.class)
class FrontendAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminUserService adminUsers;
    @MockitoBean AssetService assets;
    @MockitoBean AuthService auth;
    @MockitoBean RateLimitService rateLimits;
    @MockitoBean MyUserDetailsService users;
    @MockitoBean JWTUtils jwt;

    @Test
    @WithMockUser(roles = "USER")
    void investorCannotUseAnyAdminOperation() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
        mvc.perform(patch("/admin/users/1/status").param("status", "INACTIVE")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/assets")).andExpect(status().isForbidden());
        String asset = "{\"symbol\":\"TEST\",\"name\":\"Test\",\"assetType\":\"STOCK\",\"currentPrice\":1}";
        mvc.perform(post("/admin/assets").contentType("application/json").content(asset)).andExpect(status().isForbidden());
        mvc.perform(put("/admin/assets/1").contentType("application/json").content(asset)).andExpect(status().isForbidden());
        mvc.perform(patch("/admin/assets/1/status").contentType("application/json").content("{\"assetStatus\":\"INACTIVE\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(adminUsers, assets);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminListContainsOnlySafeAccountFields() throws Exception {
        when(adminUsers.listUsers(0, 10)).thenReturn(new PageImpl<>(List.of(
                new AdminUserResponse(1L, "First", "Last", "user@example.test", Role.USER, UserStatus.ACTIVE))));
        mvc.perform(get("/admin/users"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].emailAddress").value("user@example.test"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[0].passwordResetTokens").doesNotExist())
                .andExpect(jsonPath("$.content[0].emailVerificationTokens").doesNotExist());
    }

    @Test
    void identityComesFromAuthenticatedPrincipal() throws Exception {
        User user = new User();
        user.setEmailAddress("admin@example.test"); user.setRole(Role.ADMIN); user.setStatus(UserStatus.ACTIVE);
        var details = new MyUserDetails(user);
        var authentication = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        mvc.perform(get("/auth/me").with(authentication(authentication)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.emailAddress").value("admin@example.test"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void missingOrInvalidTokenCannotReadIdentityOrUsers() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().is4xxClientError());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer invalid" )).andExpect(status().is4xxClientError());
        mvc.perform(get("/admin/users")).andExpect(status().is4xxClientError());
    }
}
