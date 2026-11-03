# RustFS 快速上手：从启动服务到操作文件

本文使用 Docker 启动 RustFS，再通过 Spring Boot 和 Java SDK 完成一次文件上传与读取。跑通后，可以继续查阅上传文件、查询列表、下载、生成临时访问地址和删除等操作。

示例使用 Java 17、Spring Boot 3 或 4，包名统一为 `com.example.demo`。准备好 Docker Desktop 和一个可以运行测试的 Spring Boot 项目即可开始。

## 1. 认识 RustFS 和文件的存放方式

RustFS 是兼容 S3 协议的对象存储服务，适合保存图片、视频和文档。Java 程序使用 AWS SDK for Java v2 访问 RustFS。[RustFS Java 接入说明](https://docs.rustfs.com/en/developer/sdk/java)

先记住三个术语，后面的代码会一直使用它们：

| 术语 | 含义 | 本文示例 |
|---|---|---|
| 存储桶（Bucket） | 一组对象的容器 | `demo-files` |
| 对象（Object） | 文件内容及其元数据 | 内容为 `Hello, RustFS` 的文本文件 |
| 对象键（Key） | 对象在桶内的唯一名称 | `notes/hello.txt` |

`demo-files` 加上 `notes/hello.txt` 就能定位这个文件。键中的 `/` 用于组织前缀，控制台可以据此呈现类似文件夹的层级。

程序通过服务地址（Endpoint）连接 RustFS。本文使用两个地址：

| 地址 | 用途 |
|---|---|
| `http://localhost:9000` | S3 接口，供 Java SDK 访问 |
| `http://localhost:9001` | 管理控制台，供浏览器访问 |

## 2. 启动 RustFS，创建第一个存储桶

### 2.1 用 Compose 启动服务

在一个空目录创建 `compose.yaml`：

```yaml
services:
  rustfs:
    image: rustfs/rustfs:latest
    environment:
      RUSTFS_ACCESS_KEY: demo-rustfs
      RUSTFS_SECRET_KEY: demo-rustfs-secret
      RUSTFS_CONSOLE_ENABLE: "true"
    command: ["/data"]
    ports:
      - "127.0.0.1:9000:9000"
      - "127.0.0.1:9001:9001"
    volumes:
      - rustfs_data:/data

volumes:
  rustfs_data:
```

在该目录执行：

```powershell
docker compose up -d rustfs
```

这里的访问密钥用于本机练习。两个端口映射到本机，命名数据卷 `rustfs_data` 保存上传的文件；容器重建后仍可使用原来的数据。[RustFS Docker 安装说明](https://docs.rustfs.com/en/installation/container/docker)

### 2.2 登录控制台并创建桶

打开 <http://localhost:9001>，使用以下凭据登录：

- Access Key：`demo-rustfs`
- Secret Key：`demo-rustfs-secret`

进入存储桶列表，点击创建存储桶（Create Bucket），名称填写 `demo-files`，其余选项保持默认。

创建成功后，控制台中会出现一个空的 `demo-files` 桶。接下来让 Java 向它写入文件。

## 3. 连接 Java SDK，上传并读回一个文件

### 3.1 添加依赖

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.57.8</version>
</dependency>
```

本文固定使用 AWS SDK for Java v2 的 `2.57.8` 版本，方便复现。[版本发布记录](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8)

后面的验证示例使用 JUnit 5 和 `@SpringBootTest`。IDE 创建项目时通常已经包含测试依赖；空项目可添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

重新加载 Maven，等待依赖下载完成。

### 3.2 配置服务地址和凭据

在 `src/main/resources/application.yaml` 中添加：

```yaml
storage:
  endpoint: http://localhost:9000
  access-key: demo-rustfs
  secret-key: demo-rustfs-secret
```

创建 `src/main/java/com/example/demo/config/S3Config.java`。配置类应位于 Spring Boot 启动类的扫描范围内：

```java
package com.example.demo.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(
            @Value("${storage.endpoint}") String endpoint,
            @Value("${storage.access-key}") String accessKey,
            @Value("${storage.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }
}
```

配置中的几个方法分别解决以下问题：

| 配置 | 作用 |
|---|---|
| `endpointOverride(...)` | 将请求发送到本机 RustFS |
| `region(Region.US_EAST_1)` | 使用 `us-east-1` 参与请求签名，与本文 RustFS 配置对应 |
| `credentialsProvider(...)` | 使用前面设置的访问密钥进行身份验证 |
| `forcePathStyle(true)` | 使用 `主机/桶名/对象键` 的地址格式，适合本机接入 |

Spring 管理一个 `S3Client`，后续通过依赖注入复用它。[客户端配置参考](https://docs.rustfs.com/en/developer/sdk/java)

### 3.3 运行完整的上传与读取测试

创建 `src/test/java/com/example/demo/StorageTest.java`：

```java
package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class StorageTest {

    private static final String BUCKET = "demo-files";
    private static final String KEY = "notes/hello.txt";

    @Autowired
    private S3Client s3Client;

    @Test
    void uploadAndRead() {
        var upload = PutObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .contentType("text/plain; charset=utf-8")
                .build();

        s3Client.putObject(upload, RequestBody.fromString("Hello, RustFS"));

        var download = GetObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .build();

        String text = s3Client.getObjectAsBytes(download).asUtf8String();

        assertEquals("Hello, RustFS", text);
        System.out.println(BUCKET + "/" + KEY);
        System.out.println(text);
    }
}
```

在 IDE 中运行 `uploadAndRead()`。测试通过后，输出包含：

```text
demo-files/notes/hello.txt
Hello, RustFS
```

回到 RustFS 控制台，进入 `demo-files`，即可看到 `notes/hello.txt`。

这一步完成了整个调用链：Spring 注入客户端 → SDK 发送上传请求 → RustFS 保存对象 → SDK 读取对象 → 测试核对内容。

### 3.4 理解一次上传的组成

上传代码中，`PutObjectRequest` 描述目标桶、对象键和内容类型，`RequestBody` 提供真正的文件内容。两者一起传给 `putObject()`。

`contentType` 表示文件内容的 MIME 类型，例如文本为 `text/plain`，PNG 图片为 `image/png`。浏览器收到文件时会根据它决定展示方式。

在本文默认的桶配置中，再次上传相同对象键会覆盖原内容。保存多个文件时，可以给每个文件生成一个新键，例如 `images/随机标识.png`。

## 4. 常见文件操作

本节片段沿用第 3 节的 `s3Client`、`BUCKET` 和 `KEY`。每次练习保留第 3 节的完整测试，再将当前片段追加到 `uploadAndRead()` 末尾，运行并查看结果。每个片段上方列出需要补充的导入。

### 4.1 上传本地文件

添加导入：

```java
import java.nio.file.Path;
```

准备一个本地文件，例如 `C:/temp/photo.png`，然后上传：

```java
Path source = Path.of("C:/temp/photo.png");

var fileUpload = PutObjectRequest.builder()
        .bucket(BUCKET)
        .key("images/photo.png")
        .contentType("image/png")
        .build();

s3Client.putObject(fileUpload, RequestBody.fromFile(source));
```

完成后，桶中出现 `images/photo.png`。与前面的文本上传相比，这里通过 `fromFile()` 读取本地文件内容。

### 4.2 查询文件大小和内容类型

添加导入：

```java
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
```

```java
var metadata = s3Client.headObject(HeadObjectRequest.builder()
        .bucket(BUCKET)
        .key(KEY)
        .build());

System.out.println("字节数：" + metadata.contentLength());
System.out.println("内容类型：" + metadata.contentType());
```

`headObject()` 获取元数据。对于前面的文本文件，字节数为 `13`，内容类型为上传时设置的 `text/plain; charset=utf-8`。

### 4.3 按前缀列出文件

添加导入：

```java
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
```

```java
var query = ListObjectsV2Request.builder()
        .bucket(BUCKET)
        .prefix("notes/")
        .build();

for (var page : s3Client.listObjectsV2Paginator(query)) {
    for (var object : page.contents()) {
        System.out.println(object.key() + "，" + object.size() + " 字节");
    }
}
```

本例会列出 `notes/` 下的对象，包括 `notes/hello.txt`。去掉 `prefix(...)` 可遍历整个桶；分页器会在遍历过程中继续请求后续页。

### 4.4 下载文件到本地

添加导入：

```java
import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awssdk.core.sync.ResponseTransformer;
```

```java
Path target = Files.createTempDirectory("rustfs-download-")
        .resolve("hello.txt");

s3Client.getObject(
        GetObjectRequest.builder().bucket(BUCKET).key(KEY).build(),
        ResponseTransformer.toFile(target));

System.out.println("下载位置：" + target);
```

这段代码会在系统临时目录创建一个新目录，将文件保存到其中。打开输出的路径，内容为 `Hello, RustFS`。所在测试方法增加 `throws Exception` 即可处理示例中的文件系统异常。

`getObjectAsBytes()` 适合读取小文件并在内存中处理；`ResponseTransformer.toFile()` 适合将对象直接保存到磁盘。[AWS S3 操作示例](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_s3_code_examples.html)

### 4.5 删除指定文件

添加导入：

```java
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
```

完成读取和下载练习后，执行：

```java
s3Client.deleteObject(DeleteObjectRequest.builder()
        .bucket(BUCKET)
        .key(KEY)
        .build());
```

在本文默认的桶配置中，该对象随后从桶内移除。再次运行第 3 节测试，可以重新生成它。

## 5. 让浏览器通过临时地址读取文件

临时访问地址，也称预签名 URL，由后端生成，携带指定对象的访问授权和有效期。浏览器拿到地址后即可读取该文件，访问密钥保存在后端。[AWS 预签名 URL 说明](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

### 5.1 配置签名器

在第 3 节的 `S3Config` 中添加以下导入：

```java
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
```

在同一个类中增加 Bean：

```java
@Bean
public S3Presigner s3Presigner(
        @Value("${storage.endpoint}") String endpoint,
        @Value("${storage.access-key}") String accessKey,
        @Value("${storage.secret-key}") String secretKey) {
    return S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .checksumValidationEnabled(false)
                    .build())
            .build();
}
```

这里的配置同时采用路径式地址，并生成适合浏览器直接执行的 GET 签名。`checksumValidationEnabled(false)` 作用于这个签名器；前面 `S3Client` 的文件上传和读取配置保持原样。[签名器的浏览器兼容配置](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)

### 5.2 生成十分钟有效的访问地址

在 `StorageTest` 中补充导入和字段：

```java
import java.time.Duration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
```

```java
@Autowired
private S3Presigner s3Presigner;
```

以第 3 节的完整测试为起点，将下面的代码追加到 `uploadAndRead()` 末尾，运行测试：

```java
var request = GetObjectPresignRequest.builder()
        .signatureDuration(Duration.ofMinutes(10))
        .getObjectRequest(GetObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .build())
        .build();

String url = s3Presigner.presignGetObject(request).url().toString();
System.out.println(url);
```

复制输出的完整地址到浏览器，页面会显示 `Hello, RustFS`。将对象键换成已经上传的图片键，浏览器即可按图片的内容类型展示。

实际接口可以先判断当前用户对文件的查看权限，再生成临时地址。数据库保存桶名、对象键及业务所需的文件信息；每次访问时生成新的临时地址。

## 6. 接收前端上传的文件

前面的示例演示了 SDK 本身的用法。本节将它接到 Spring MVC，形成一个可调用的上传接口。

在包含 Spring MVC 依赖的项目中创建 `src/main/java/com/example/demo/FileController.java`：

```java
package com.example.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final S3Client s3Client;

    public FileController(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @PostMapping
    public UploadResult upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        String key = "uploads/" + UUID.randomUUID();
        String contentType = file.getContentType();
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        var request = PutObjectRequest.builder()
                .bucket("demo-files")
                .key(key)
                .contentType(contentType)
                .build();

        try (InputStream input = file.getInputStream()) {
            s3Client.putObject(request,
                    RequestBody.fromInputStream(input, file.getSize()));
        }

        return new UploadResult(key);
    }

    public record UploadResult(String key) {}
}
```

这个练习将上传操作集中放在一个控制器里，便于看清调用过程。接入业务后，可将文件存储操作提取到 Service，供多个接口复用。

启动 Spring Boot，在终端执行：

```powershell
curl.exe -X POST http://localhost:8080/api/files -F "file=@C:/temp/photo.png"
```

返回内容类似：

```json
{"key":"uploads/8f566d3a-1dbe-4764-8dce-e3bff32b9961"}
```

控制台中会出现对应对象。这里的对象键使用随机标识，原文件名可以作为业务信息另行保存。

上传较大文件时，在 `application.yaml` 中设置接口接收上限：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 25MB
```

第一项限制单个文件，第二项限制整次请求。浏览器表单或前端请求的文件字段名使用 `file`，与 `@RequestParam("file")` 对应。

## 7. 调整访问地址与管理服务

### 7.1 从其他设备访问

本文的端口绑定到 `127.0.0.1`，用于本机练习。开放局域网访问时，将端口映射改为 `9000:9000` 和 `9001:9001`，并在 Windows 防火墙中为所需设备开放相应端口。

假设电脑的局域网 IP 是 `192.168.2.15`，手机使用的文件地址应包含 `192.168.2.15:9000`。生成给手机使用的临时地址时，将签名器的 Endpoint 设置为这个可达地址，然后重新签名；地址中的主机和端口也参与签名计算。

当 Spring Boot 同样运行在 Compose 网络中时，SDK 可以通过服务名 `http://rustfs:9000` 访问存储；对外生成的临时地址使用浏览器可达的 Endpoint。两者可分别配置。

### 7.2 查看日志与停止服务

```powershell
docker compose ps rustfs
docker compose logs --tail 100 rustfs
docker compose stop rustfs
docker compose start rustfs
```

`stop` 和 `start` 用于停止、启动现有容器，命名数据卷继续保留。本文镜像采用官方示例中的 `latest` 标签；团队需要复现同一环境时，可以固定为确认过的镜像版本。

## 8. 按报错定位问题

| 现象 | 优先检查 | 处理方式 |
|---|---|---|
| 连接被拒绝 | 容器状态、端口映射、Endpoint | 用 `docker compose ps rustfs` 确认服务，再核对 `9000` 端口 |
| SDK 返回页面内容或解析响应失败 | Endpoint 是否填成控制台地址 | SDK 使用 `http://localhost:9000`，管理控制台使用 `9001` |
| `NoSuchBucket` | 桶名及创建结果 | 在控制台确认 `demo-files` 已创建，核对大小写 |
| `NoSuchKey` | 桶名、完整对象键 | 使用上传时返回的键，例如 `notes/hello.txt` |
| `AccessDenied` 或 `InvalidAccessKeyId` | SDK 凭据、对象访问权限 | 对齐容器环境变量与 `storage` 配置；浏览器使用有效临时地址 |
| `SignatureDoesNotMatch` | 密钥、系统时间、签名地址 | 核对凭据并同步系统时间，使用按最终访问地址生成的完整 URL |
| 请求解析到 `demo-files.localhost` 等主机 | 客户端的地址模式 | 核对 `forcePathStyle(true)` 或签名器的路径式配置 |
| 上传接口报文件大小超限 | Spring MVC 的上传限制 | 调整 `spring.servlet.multipart` 的两个大小配置 |
| 浏览器脚本请求报 CORS，后端调用正常 | 浏览器跨域规则 | 为桶配置前端来源、请求方法和请求头，参考 [RustFS CORS 配置](https://docs.rustfs.com/en/administration/cors) |

第 3 节的上传与读取测试可用来单独验证 SDK 到 RustFS 的连接。该测试通过后，再检查 Spring MVC 接口、浏览器请求和业务逻辑，可以逐步缩小问题范围。
