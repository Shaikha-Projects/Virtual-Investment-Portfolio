package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.Security.JWTUtils;
import com.ga.investmentportfolio.Security.MyUserDetailsService;
import com.ga.investmentportfolio.Security.SecurityConfiguration;
import com.ga.investmentportfolio.Service.NotificationService;
import com.ga.investmentportfolio.Service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfiguration.class, NotificationService.class})
class NotificationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired NotificationService notifications;
    @MockitoBean JWTUtils jwt;
    @MockitoBean MyUserDetailsService users;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void subscriptionRequiresAuthentication() throws Exception {
        mvc.perform(get("/notifications/subscribe")).andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "user@example.test")
    void subscriptionStreamsNamedTradeEventsAndSurvivesReplacement() throws Exception {
        var stream = mvc.perform(get("/notifications/subscribe"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        notifications.sendNotification("user@example.test", "Purchase completed: 0.01 shares of AAPL");
        var replacement = notifications.subscribe("user@example.test");
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk())
                .andExpect(content().string(containsString("event:investment-notification")))
                .andExpect(content().string(containsString("event:subscription-replaced")))
                .andExpect(content().string(containsString("data:Purchase completed: 0.01 shares of AAPL")));
        notifications.sendNotification("user@example.test", "Sale completed: 0.01 shares of AAPL");
        replacement.complete();
    }
}
