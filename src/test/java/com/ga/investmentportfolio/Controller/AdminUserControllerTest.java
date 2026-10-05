package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.Security.JWTUtils;
import com.ga.investmentportfolio.Security.MyUserDetailsService;
import com.ga.investmentportfolio.Service.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
public class AdminUserControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;

    @MockitoBean
    private MyUserDetailsService myUserDetailsService;

    @MockitoBean
    private JWTUtils jwtUtils;

    @Test
    @DisplayName("When normal user tries to deactivate user then access is forbidden")
    @WithMockUser(roles = "USER")
    public void whenNormalUserTriesToDeactivateUserThenAccessIsForbidden() throws Exception {

        mockMvc.perform(patch("/admin/users/1/deactivate")).andExpect(status().isForbidden());
    }
}
