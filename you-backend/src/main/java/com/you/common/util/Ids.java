package com.you.common.util;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();

  public static UUID next() {
    long ms = System.currentTimeMillis();
    return new UUID(
        (ms << 16) | 0x7000 | (RANDOM.nextInt(4096)),
        (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
  }
}
