package com.you.user.service;

import com.you.common.exception.BusinessException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginLimiter {
  private record Window(long start, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final int accountLimit, ipLimit;

  public LoginLimiter(
      @Value("${auth.login.account-per-minute:10}") int a,
      @Value("${auth.login.ip-per-minute:30}") int i) {
    accountLimit = a;
    ipLimit = i;
  }

  public synchronized void check(String account, String ip) {
    long now = System.currentTimeMillis();
    windows.entrySet().removeIf(e -> now - e.getValue().start() >= 60000);
    consume("account:" + account, accountLimit, now);
    consume("ip:" + ip, ipLimit, now);
  }

  private void consume(String key, int limit, long now) {
    var w = windows.getOrDefault(key, new Window(now, 0));
    if (w.count() >= limit) throw new BusinessException(429, "RATE_LIMITED", "登录尝试过于频繁");
    windows.put(key, new Window(w.start(), w.count() + 1));
  }
}
