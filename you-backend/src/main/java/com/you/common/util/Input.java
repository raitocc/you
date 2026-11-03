package com.you.common.util;

import com.you.common.exception.BusinessException;
import java.util.*;

public final class Input {
  public static void fields(Map<String, Object> m, String... names) {
    if (m == null || !m.keySet().equals(Set.of(names))) throw BusinessException.invalid();
  }

  public static String string(Map<String, Object> m, String key) {
    if (!(m.get(key) instanceof String s)) throw BusinessException.invalid();
    return s;
  }

  public static String username(String s) {
    if (!s.matches("[A-Za-z][A-Za-z0-9_]{3,63}")) throw BusinessException.invalid();
    return s.toLowerCase(Locale.ROOT);
  }

  public static String nickname(String s) {
    s = s.strip();
    int n = s.codePointCount(0, s.length());
    if (n < 1 || n > 64) throw BusinessException.invalid();
    return s;
  }

  public static UUID uuid(Object s) {
    try {
      if (!(s instanceof String v)
          || !v.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
        throw BusinessException.invalid();
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      throw BusinessException.invalid();
    }
  }
}
