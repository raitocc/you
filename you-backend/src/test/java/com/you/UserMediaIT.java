package com.you;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "auth.jwt.secret=integration-test-secret-at-least-32-bytes",
      "storage.bucket=you-contract-tests"
    })
class UserMediaIT {
  @LocalServerPort int port;
  @Autowired S3Client s3;
  @Autowired JdbcTemplate db;
  @Autowired com.you.media.service.StorageService storage;
  @Autowired com.you.media.mapper.MediaMapper media;
  @Autowired com.you.media.mapper.UploadMapper uploads;
  @Autowired com.you.media.service.ImageValidator images;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  final JsonMapper json = JsonMapper.builder().build();
  final List<UUID> users = new ArrayList<>();
  String token;

  HttpResponse<String> call(String method, String path, String body, String auth) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (auth != null) b.header("Authorization", "Bearer " + auth);
    if (body != null) b.header("Content-Type", "application/json");
    return http.send(
        b.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode data(HttpResponse<String> r, int status) {
    assertEquals(status, r.statusCode(), r.body());
    return json.readTree(r.body()).get("data");
  }

  String register(String suffix) throws Exception {
    String username = "it_" + UUID.randomUUID().toString().replace("-", "");
    var u =
        data(
            call(
                "POST",
                "/user/users",
                "{\"username\":\""
                    + username
                    + "\",\"password\":\"Example12345\",\"nickname\":\" Test \"}",
                null),
            201);
    users.add(UUID.fromString(u.get("id").asText()));
    assertEquals("Test", u.get("nickname").asText());
    assertEquals(
        409,
        call(
                "POST",
                "/user/users",
                "{\"username\":\""
                    + username.toUpperCase()
                    + "\",\"password\":\"Other12345\",\"nickname\":\"X\"}",
                null)
            .statusCode());
    return data(
            call(
                "POST",
                "/user/sessions",
                "{\"username\":\"" + username + "\",\"password\":\"Example12345\"}",
                null),
            200)
        .get("accessToken")
        .asText();
  }

  // Opt in with -Drestart.jar=<packaged jar> to verify persistence in a fresh JVM.
  void verifyFreshJvm(String path, String expectedFile) throws Exception {
    String jar = System.getProperty("restart.jar");
    if (jar == null) return;
    int restartPort;
    try (var socket = new java.net.ServerSocket(0)) {
      restartPort = socket.getLocalPort();
    }
    String javaExecutable =
        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var builder =
        new ProcessBuilder(
            javaExecutable,
            "-jar",
            java.nio.file.Path.of(jar).toAbsolutePath().toString(),
            "--server.port=" + restartPort,
            "--storage.bucket=you-contract-tests");
    builder.environment().put("JWT_SECRET", "integration-test-secret-at-least-32-bytes");
    builder
        .redirectErrorStream(true)
        .redirectOutput(java.nio.file.Path.of("target", "restart-verification.log").toFile());
    var process = builder.start();
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
      while (true) {
        assertTrue(process.isAlive(), "Fresh JVM exited; see target/restart-verification.log");
        try {
          var response =
              http.send(
                  HttpRequest.newBuilder(URI.create("http://localhost:" + restartPort + path))
                      .header("Authorization", "Bearer " + token)
                      .POST(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(expectedFile, data(response, 200).get("fileId").asText());
          break;
        } catch (java.net.ConnectException e) {
          if (System.nanoTime() > deadline) throw e;
          Thread.sleep(150);
        }
      }
    } finally {
      process.destroy();
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @AfterEach
  void cleanup() {
    db.update(
        "DELETE FROM gathering_member WHERE gathering_id IN (SELECT id FROM gathering WHERE"
            + " name='contract-test')");
    db.update("DELETE FROM gathering WHERE name='contract-test'");
    db.update(
        "DELETE FROM circle_member WHERE circle_id IN (SELECT id FROM circle WHERE"
            + " name='contract-test')");
    db.update(
        "DELETE FROM circle_share WHERE circle_id IN (SELECT id FROM circle WHERE"
            + " name='contract-test')");
    db.update("DELETE FROM circle WHERE name='contract-test'");
    for (UUID id : users)
      db.update("DELETE FROM moment WHERE author_user_id=UUID_TO_BIN(?)", id.toString());
    for (UUID id : users) db.update("DELETE FROM app_user WHERE id=UUID_TO_BIN(?)", id.toString());
    for (UUID id : users) {
      var temporaryKeys =
          db.queryForList(
              "SELECT storage_key FROM media_upload WHERE owner_user_id=UUID_TO_BIN(?)",
              String.class,
              id.toString());
      for (String key : temporaryKeys)
        s3.deleteObject(b -> b.bucket("you-contract-tests").key(key));
      db.update("DELETE FROM media_upload WHERE owner_user_id=UUID_TO_BIN(?)", id.toString());
      var keys =
          db.queryForList(
              "SELECT storage_key FROM media_file WHERE owner_user_id=UUID_TO_BIN(?)",
              String.class,
              id.toString());
      for (String key : keys) s3.deleteObject(b -> b.bucket("you-contract-tests").key(key));
      db.update("DELETE FROM media_file WHERE owner_user_id=UUID_TO_BIN(?)", id.toString());
    }
  }

  @Test
  void fullUserUploadAvatarFlow() throws Exception {
    try {
      s3.headBucket(b -> b.bucket("you-contract-tests"));
    } catch (Exception e) {
      s3.createBucket(b -> b.bucket("you-contract-tests"));
    }
    token = register("a");
    String other = register("b");
    assertEquals(401, call("GET", "/user/users/me", null, null).statusCode());
    assertEquals(400, call("PUT", "/user/users/me", "{\"nickname\":\"x\"}", token).statusCode());
    assertEquals(
        400,
        call(
                "PUT",
                "/user/users/me",
                "{\"nickname\":\"x\",\"avatarFileId\":null,\"id\":\"x\"}",
                token)
            .statusCode());
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
    byte[] bytes = out.toByteArray();
    var upload =
        data(
            call(
                "POST",
                "/media/uploads",
                "{\"mimeType\":\"image/png\",\"byteSize\":" + bytes.length + "}",
                token),
            201);
    String complete = "/media/uploads/" + upload.get("uploadId").asText() + "/complete";
    assertEquals(400, call("POST", complete, null, token).statusCode());
    assertEquals(404, call("POST", complete, null, other).statusCode());
    var put =
        HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
            .header("Content-Type", "image/png")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    assertEquals(200, http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode());
    // Separate service instances share no Java state and serialize through MySQL row locks.
    var first =
        new com.you.media.service.UploadService(
            storage, media, images, uploads, transactionManager);
    var second =
        new com.you.media.service.UploadService(
            storage, media, images, uploads, transactionManager);
    UUID uploadId = UUID.fromString(upload.get("uploadId").asText());
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    String file;
    try {
      var results = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
      for (int i = 0; i < 4; i++) {
        var service = i % 2 == 0 ? first : second;
        results.add(pool.submit(() -> service.complete(users.get(0), uploadId)));
      }
      file = results.get(0).get().get("fileId").toString();
      for (var r : results) assertEquals(file, r.get().get("fileId").toString());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM media_file WHERE owner_user_id=UUID_TO_BIN(?)",
            Integer.class,
            users.get(0).toString()));
    verifyFreshJvm(complete, file);
    // Expiry of the PUT credential does not invalidate a retained confirmation result.
    db.update(
        "UPDATE media_upload SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE"
            + " id=UUID_TO_BIN(?)",
        uploadId.toString());
    assertEquals(file, data(call("POST", complete, null, token), 200).get("fileId").asText());
    assertEquals(
        400,
        call(
                "PUT",
                "/user/users/me",
                "{\"nickname\":\"Other\",\"avatarFileId\":\"" + file + "\"}",
                other)
            .statusCode());
    String avatar =
        data(
                call(
                    "PUT",
                    "/user/users/me",
                    "{\"nickname\":\"Updated\",\"avatarFileId\":\"" + file + "\"}",
                    token),
                200)
            .get("avatarContentPath")
            .asText();
    var redirect = call("GET", avatar, null, token);
    assertEquals(302, redirect.statusCode(), redirect.body());
    assertEquals("private, no-store", redirect.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(404, call("GET", avatar, null, other).statusCode());
    // Ended gatherings keep mutual avatar access until membership or gathering is deleted.
    String gathering = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO gathering(id,created_at,updated_at,creator_user_id,name,status,invite_code)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),'contract-test',2,?)",
        gathering,
        users.get(0).toString(),
        UUID.randomUUID().toString().substring(0, 16));
    for (UUID uid : users)
      db.update(
          "INSERT INTO gathering_member(id,created_at,updated_at,gathering_id,user_id)"
              + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),UUID_TO_BIN(?))",
          UUID.randomUUID().toString(),
          gathering,
          uid.toString());
    assertEquals(302, call("GET", avatar, null, other).statusCode());
    db.update(
        "UPDATE gathering SET deleted_at=UTC_TIMESTAMP(3) WHERE id=UUID_TO_BIN(?)", gathering);
    assertEquals(404, call("GET", avatar, null, other).statusCode());
    String circle = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO circle(id,created_at,updated_at,creator_user_id,name,invite_code)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),'contract-test',?)",
        circle,
        users.get(1).toString(),
        UUID.randomUUID().toString().substring(0, 16));
    db.update(
        "INSERT INTO circle_member(id,created_at,updated_at,circle_id,user_id)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),UUID_TO_BIN(?))",
        UUID.randomUUID().toString(),
        circle,
        users.get(1).toString());
    String moment = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO moment(id,created_at,updated_at,author_user_id,body,memory_time)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),'test',UTC_TIMESTAMP(3))",
        moment,
        users.get(0).toString());
    db.update(
        "INSERT INTO"
            + " circle_share(id,created_at,updated_at,circle_id,object_type,object_id,sharer_user_id,shared_at)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),'moment',UUID_TO_BIN(?),UUID_TO_BIN(?),UTC_TIMESTAMP(3))",
        UUID.randomUUID().toString(),
        circle,
        moment,
        users.get(0).toString());
    assertEquals(302, call("GET", avatar, null, other).statusCode());
    db.update("UPDATE moment SET deleted_at=UTC_TIMESTAMP(3) WHERE id=UUID_TO_BIN(?)", moment);
    assertEquals(404, call("GET", avatar, null, other).statusCode());
    db.update(
        "INSERT INTO circle_member(id,created_at,updated_at,circle_id,user_id)"
            + " VALUES(UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UUID_TO_BIN(?),UUID_TO_BIN(?))",
        UUID.randomUUID().toString(),
        circle,
        users.get(0).toString());
    assertEquals(302, call("GET", avatar, null, other).statusCode());
    db.update("UPDATE circle SET deleted_at=UTC_TIMESTAMP(3) WHERE id=UUID_TO_BIN(?)", circle);
    assertEquals(404, call("GET", avatar, null, other).statusCode());
    assertEquals(
        200,
        http.send(
                HttpRequest.newBuilder(
                        URI.create(redirect.headers().firstValue("Location").orElseThrow()))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    // Overwriting the temporary object cannot alter the confirmed object.
    http.send(
        HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
            .header("Content-Type", "image/png")
            .PUT(HttpRequest.BodyPublishers.ofString("corrupt"))
            .build(),
        HttpResponse.BodyHandlers.discarding());
    assertArrayEquals(
        bytes,
        http.send(
                HttpRequest.newBuilder(
                        URI.create(redirect.headers().firstValue("Location").orElseThrow()))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .body());
    assertEquals(200, call("DELETE", "/user/sessions/current", null, token).statusCode());
    assertEquals(200, call("GET", "/user/users/me", null, token).statusCode());
    assertTrue(
        data(
                call(
                    "PUT",
                    "/user/users/me",
                    "{\"nickname\":\"Updated\",\"avatarFileId\":null}",
                    token),
                200)
            .get("avatarContentPath")
            .isNull());
    assertEquals(404, call("GET", avatar, null, token).statusCode());
    db.update(
        "UPDATE media_upload SET retain_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE"
            + " id=UUID_TO_BIN(?)",
        uploadId.toString());
    assertEquals(404, call("POST", complete, null, token).statusCode());
    first.cleanup();
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM media_upload WHERE id=UUID_TO_BIN(?)",
            Integer.class,
            uploadId.toString()));
    assertNotNull(
        media.find(UUID.fromString(file))); // Expiring a receipt never deletes the confirmed file.
    var expired =
        data(
            call("POST", "/media/uploads", "{\"mimeType\":\"image/png\",\"byteSize\":1}", token),
            201);
    db.update(
        "UPDATE media_upload SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE"
            + " id=UUID_TO_BIN(?)",
        expired.get("uploadId").asText());
    assertEquals(
        410,
        call(
                "POST",
                "/media/uploads/" + expired.get("uploadId").asText() + "/complete",
                null,
                token)
            .statusCode());
  }
}
