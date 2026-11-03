package com.you.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.you.common.exception.BusinessException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
  private final byte[] secret;
  private final String issuer, audience;

  public JwtService(
      @Value("${auth.jwt.secret}") String secret,
      @Value("${auth.jwt.issuer:you}") String issuer,
      @Value("${auth.jwt.audience:you-client}") String audience) {
    this.secret = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (this.secret.length < 32)
      throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
    this.issuer = issuer;
    this.audience = audience;
  }

  public String issue(UUID id) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .subject(id.toString())
              .issuer(issuer)
              .audience(audience)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(604800)))
              .jwtID(UUID.randomUUID().toString())
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public UUID verify(String token) {
    try {
      var jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
          || !jwt.verify(new MACVerifier(secret))) throw BusinessException.unauthorized();
      var c = jwt.getJWTClaimsSet();
      var now = new Date();
      if (!issuer.equals(c.getIssuer())
          || !c.getAudience().contains(audience)
          || c.getExpirationTime() == null
          || !c.getExpirationTime().after(now)
          || (c.getNotBeforeTime() != null && c.getNotBeforeTime().after(now)))
        throw BusinessException.unauthorized();
      return UUID.fromString(c.getSubject());
    } catch (Exception e) {
      throw BusinessException.unauthorized();
    }
  }
}
