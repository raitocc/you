package com.you;

import static org.junit.jupiter.api.Assertions.*;

import com.you.common.exception.BusinessException;
import com.you.common.util.*;
import com.you.config.JwtService;
import com.you.media.service.ImageValidator;
import com.you.user.service.LoginLimiter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void jwtPinsSignatureIssuerAndAudience() {
    var a = new JwtService("test-secret-with-more-than-32-bytes", "you", "client");
    var id = Ids.next();
    String token = a.issue(id);
    assertEquals(id, a.verify(token));
    assertThrows(
        BusinessException.class,
        () -> new JwtService("different-secret-more-than-32-bytes", "you", "client").verify(token));
    assertThrows(
        BusinessException.class,
        () ->
            new JwtService("test-secret-with-more-than-32-bytes", "other", "client").verify(token));
    assertThrows(
        BusinessException.class,
        () -> new JwtService("test-secret-with-more-than-32-bytes", "you", "other").verify(token));
    assertThrows(BusinessException.class, () -> a.verify("bad"));
  }

  @Test
  void limitsAccountAndIp() {
    var a = new LoginLimiter(10, 30);
    for (int i = 0; i < 10; i++) a.check("a", "ip" + i);
    assertEquals(429, assertThrows(BusinessException.class, () -> a.check("a", "other")).status);
    var b = new LoginLimiter(10, 30);
    for (int i = 0; i < 30; i++) b.check("a" + i, "ip");
    assertEquals(429, assertThrows(BusinessException.class, () -> b.check("another", "ip")).status);
  }

  @Test
  void imageChecksActualFormatAndSize() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    byte[] bytes = out.toByteArray();
    var validator = new ImageValidator();
    assertEquals("image/png", validator.validate(bytes, "image/png", bytes.length));
    assertEquals(
        "INVALID_IMAGE",
        assertThrows(
                BusinessException.class,
                () -> validator.validate(bytes, "image/jpeg", bytes.length))
            .code);
    assertThrows(
        BusinessException.class, () -> validator.validate(bytes, "image/png", bytes.length + 1));
    assertThrows(
        BusinessException.class, () -> validator.validate(new byte[] {1, 2}, "image/png", 2));
  }

  @Test
  void inputAndUuidContract() {
    assertEquals("abc_", Input.username("AbC_"));
    assertEquals("昵称", Input.nickname("  昵称  "));
    assertThrows(
        BusinessException.class,
        () -> Input.fields(Map.of("nickname", "x"), "nickname", "avatarFileId"));
    assertThrows(BusinessException.class, () -> Input.username("1abc"));
    UUID id = Ids.next();
    assertEquals(7, id.version());
    assertEquals(2, id.variant());
  }
}
