package com.you.youbackend.inbox.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信箱模块
 */
@RestController
@Tag(name = "信箱")
@RequestMapping("/inbox")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class InboxController {



}
