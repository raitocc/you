package com.you.youbackend;

import com.sun.net.httpserver.HttpServer;
import com.you.youbackend.common.config.S3Config;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.media.controller.MediaController;
import com.you.youbackend.media.service.MediaService;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立验证表单、参数校验及真实 HTTP PUT，测试过程通过 standalone MockMvc 避开数据库启动。
 */
class MediaUploadTest {

    private HttpServer server;
    private MockMvc mvc;
    private String endpoint;
    private final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private final AtomicInteger receivedRequests = new AtomicInteger();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBytes = new AtomicReference<>();

//    @BeforeEach
//    void setUp() throws Exception {
//        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
//        server.createContext("/you-files/images/sample", exchange -> {
//            receivedRequests.incrementAndGet();
//            receivedMethod.set(exchange.getRequestMethod());
//            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
//            receivedQuery.set(exchange.getRequestURI().getRawQuery());
//            receivedBytes.set(exchange.getRequestBody().readAllBytes());
//            exchange.getResponseHeaders().add("ETag", "\"test-etag\"");
//            byte[] body = upstreamStatus.get() == 200 ? new byte[0]
//                    : "<Error><Code>SignatureDoesNotMatch</Code></Error>".getBytes();
//            exchange.sendResponseHeaders(upstreamStatus.get(), body.length == 0 ? -1 : body.length);
//            if (body.length > 0) {
//                exchange.getResponseBody().write(body);
//            }
//            exchange.close();
//        });
//        server.start();
//        endpoint = "http://localhost:" + server.getAddress().getPort();
//        mvc = createMvc(endpoint);
//    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void forwardsBinaryContentTypeAndUnchangedSignatureQuery() throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 255, 13, 10};
        String query = "X-Amz-Credential=you%2F20261104&X-Amz-Signature=sample";
        mvc.perform(multipart("/media/uploads/test")
                        .file(new MockMultipartFile("file", "sample.png", "image/png", bytes))
                        .param("url", endpoint + "/you-files/images/sample?" + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.statusCode").value(200))
                .andExpect(jsonPath("$.data.etag").value("\"test-etag\""));

        assertEquals("PUT", receivedMethod.get());
        assertEquals("image/png", receivedContentType.get());
        assertEquals(query, receivedQuery.get());
        assertArrayEquals(bytes, receivedBytes.get());
    }

    @Test
    void rejectsAnotherTargetBeforeSendingAnyRequest() throws Exception {
        mvc.perform(multipart("/media/uploads/test")
                        .file(image())
                        .param("url", "http://example.com/you-files/images/sample"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, receivedRequests.get());
    }

    @Test
    void reportsStorageRejectionWithUnifiedResponse() throws Exception {
        upstreamStatus.set(403);
        mvc.perform(multipart("/media/uploads/test")
                        .file(image())
                        .param("url", endpoint + "/you-files/images/sample"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPLOAD_FAILED"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(result -> assertTrue(result.getResponse()
                        .getContentAsString().contains("SignatureDoesNotMatch")));
    }

    @Test
    void validatesRequiredUrlBeforeForwarding() throws Exception {
        mvc.perform(multipart("/media/uploads/test").file(image()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, receivedRequests.get());
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mvc.perform(multipart("/media/uploads/test")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .param("url", endpoint + "/you-files/images/sample"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, receivedRequests.get());
    }

    /**
     * 显式开启时使用本机 RustFS，上传唯一测试对象、读回核对并清理该对象。
     */
//    @Test
//    @EnabledIfSystemProperty(named = "rustfs.test.enabled", matches = "true")
//    void uploadsRealImageToRustFsUsingSdkPresignedUrl() throws Exception {
//        String rustfsEndpoint = "http://localhost:9000";
//        String key = "images/upload-test-" + UUID.randomUUID() + ".png";
//        byte[] image = Base64.getDecoder().decode(
//                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII=");
//        S3Config config = new S3Config();
//        try (S3Client storage = config.s3Client(rustfsEndpoint, "you", "you");
//             S3Presigner presigner = config.s3Presigner(rustfsEndpoint, "you", "you")) {
//            PutObjectRequest put = PutObjectRequest.builder()
//                    .bucket("you-files").key(key).contentType("image/png").build();
//            String url = presigner.presignPutObject(builder -> builder
//                            .signatureDuration(Duration.ofMinutes(10)).putObjectRequest(put))
//                    .url().toString();
//            try {
//                createMvc(rustfsEndpoint).perform(multipart("/media/uploads/test")
//                                .file(new MockMultipartFile("file", "pixel.png", "image/png", image))
//                                .param("url", url))
//                        .andExpect(status().isOk())
//                        .andExpect(jsonPath("$.data.statusCode").value(200));
//                byte[] stored = storage.getObjectAsBytes(GetObjectRequest.builder()
//                        .bucket("you-files").key(key).build()).asByteArray();
//                assertArrayEquals(image, stored);
//            } finally {
//                storage.deleteObject(builder -> builder.bucket("you-files").key(key));
//            }
//        }
//    }

    @Test
    void validatesMalformedUrlBeforeForwarding() throws Exception {
        mvc.perform(multipart("/media/uploads/test")
                        .file(image())
                        .param("url", "http://localhost:9000/invalid%url"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, receivedRequests.get());
    }
//    private MockMvc createMvc(String storageEndpoint) {
//        var service = new MediaService(mock(S3Presigner.class), "you-files", storageEndpoint);
//        return MockMvcBuilders.standaloneSetup(new MediaController(service))
//                .setControllerAdvice(new GlobalExceptionHandler())
//                .build();
//    }

    private MockMultipartFile image() {
        return new MockMultipartFile("file", "sample.png", "image/png", new byte[]{1, 2, 3});
    }
}
