package com.you.user.controller;

import com.you.common.response.ApiResponse;
import com.you.common.util.Input;
import com.you.config.JwtService;
import com.you.user.service.*;
import com.you.user.vo.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "用户")
@RestController
@RequestMapping("/user")
public class UserController {
  private final UserService users;
  private final JwtService jwt;
  private final LoginLimiter limiter;
  private final AvatarService avatars;

  public UserController(
      UserService users, JwtService jwt, LoginLimiter limiter, AvatarService avatars) {
    this.users = users;
    this.jwt = jwt;
    this.limiter = limiter;
    this.avatars = avatars;
  }

  @PostMapping("/users")
  public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
    Input.fields(body, "username", "password", "nickname");
    return ResponseEntity.status(201)
        .body(
            ApiResponse.ok(
                UserView.of(
                    users.register(
                        Input.string(body, "username"),
                        Input.string(body, "password"),
                        Input.string(body, "nickname")))));
  }

  @PostMapping("/sessions")
  public ApiResponse<?> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    Input.fields(body, "username", "password");
    String username = Input.username(Input.string(body, "username"));
    limiter.check(username, req.getRemoteAddr());
    var u = users.login(username, Input.string(body, "password"));
    return ApiResponse.ok(
        Map.of(
            "accessToken",
            jwt.issue(u.getId()),
            "tokenType",
            "Bearer",
            "expiresIn",
            604800,
            "user",
            UserView.of(u)));
  }

  @DeleteMapping("/sessions/current")
  public ApiResponse<?> logout() {
    return ApiResponse.ok(Map.of("loggedOut", true));
  }

  @GetMapping("/users/me")
  public ApiResponse<?> current(@RequestAttribute UUID userId) {
    return ApiResponse.ok(UserView.of(users.current(userId)));
  }

  @PutMapping("/users/me")
  public ApiResponse<?> update(
      @RequestAttribute UUID userId, @RequestBody Map<String, Object> body) {
    Input.fields(body, "nickname", "avatarFileId");
    return ApiResponse.ok(
        UserView.of(
            users.update(
                userId,
                Input.string(body, "nickname"),
                body.get("avatarFileId") == null ? null : Input.uuid(body.get("avatarFileId")))));
  }

  @GetMapping("/users/{targetId}/avatar")
  public ResponseEntity<?> avatar(@RequestAttribute UUID userId, @PathVariable UUID targetId) {
    return ResponseEntity.status(302)
        .header("Location", avatars.url(userId, targetId))
        .header("Cache-Control", "private, no-store")
        .build();
  }
}
