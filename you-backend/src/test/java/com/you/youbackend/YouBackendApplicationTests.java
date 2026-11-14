package com.you.youbackend;

import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
class YouBackendApplicationTests {

    @Autowired
    UserService userService;
    @Autowired
    CircleService circleService;

    @Test
    void contextLoads() {
        userService.ResetPassword(UUID.fromString("01a1d42d-b99f-7427-bf5e-456c543e2b9c"),"mcandy133299");
    }

}
