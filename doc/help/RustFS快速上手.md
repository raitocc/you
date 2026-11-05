# RustFS：客户端、文件操作、预签名与上传事件

RustFS 提供兼容 S3 的对象存储接口，Java 可以使用 AWS S3 SDK 操作它。本文介绍服务启动、客户端与签名器、文件常用操作，以及上传事件如何发送到 RabbitMQ。示例使用 Java 17、Spring Boot 4，省略常规 import。

## 1. RustFS 启动与对象存储概念

在 `compose.yaml` 中添加：

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

执行 `docker compose up -d rustfs`。打开 `http://localhost:9001`，使用上面的 Access Key 和 Secret Key 登录，创建私有桶 `demo-files`。`9001` 提供管理页面，`9000` 提供程序调用的 S3 接口。

对象存储通过“桶名 + 对象键”定位文件：

| 概念 | 含义 | 示例 |
| --- | --- | --- |
| 桶（Bucket） | 存放对象的容器 | `demo-files` |
| 对象（Object） | 文件内容及其元数据 | 一张图片及其 MIME 类型等信息 |
| 对象键（Key） | 对象在桶内的名称 | `images/photo.png` |
| Endpoint | S3 接口地址 | `http://localhost:9000` |

对象键中的 `/` 用于组织前缀，控制台可以按目录形式展示。上传到同一个键会替换当前对象；为每个文件生成新键，可以保留各自的内容。

## 2. S3Client 与 S3Presigner 配置

`S3Client` 是执行器：调用上传、下载、查询元数据等方法时，它会向 RustFS 发出 HTTP 请求。例如，服务端接收文件后再上传，实际文件字节就经过服务端。

`S3Presigner` 是签名器：它根据凭据、目标对象、HTTP 方法、有效期等计算临时授权 URL。生成 URL 的过程在本地完成；客户端随后持 URL 直接请求 RustFS，文件字节可以直接在客户端与 RustFS 之间传输。[RustFS Java 接入](https://docs.rustfs.com/en/developer/sdk/java)

在 `pom.xml` 添加 `software.amazon.awssdk:s3:2.57.8`。在 `application.yaml` 配置：

```yaml
storage:
  endpoint: http://localhost:9000
  access-key: demo-rustfs
  secret-key: demo-rustfs-secret
```

创建 `S3Config`，分别注册客户端和签名器：

```java
@Configuration
public class S3Config {
    @Bean
    public S3Client s3Client(@Value("${storage.endpoint}") String endpoint,
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

    @Bean
    public S3Presigner s3Presigner(@Value("${storage.endpoint}") String endpoint,
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
}
```

`S3Client`、`S3Configuration` 位于 `software.amazon.awssdk.services.s3`，`S3Presigner` 位于其 `presigner` 子包。凭据类型位于 `software.amazon.awssdk.auth.credentials`。

Endpoint 决定请求发往哪里，凭据用于请求签名，Region 参与签名计算。路径式访问生成 `主机/桶/对象键` 的地址，适合这里的本机部署。签名器示例关闭校验和验证，便于生成客户端直接使用的 URL；签名请求要求的头仍需随请求发送。

## 3. 文件上传、读取、查询与删除

在 Service 中通过构造器注入 `S3Client`，变量名为 `s3Client`。下面沿用同一个桶和对象键；请求类型来自 `software.amazon.awssdk.services.s3.model`，`RequestBody` 来自 `software.amazon.awssdk.core.sync`。

### 3.1 上传文件

上传由两部分组成：`PutObjectRequest` 描述放到哪里、保存什么元数据，`RequestBody` 提供实际内容。

```java
String bucket = "demo-files";
String key = "notes/hello.txt";

PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType("text/plain")
        .build();
s3Client.putObject(request, RequestBody.fromString("Hello, RustFS"));
```

执行后，控制台应出现 `notes/hello.txt`。上传本地文件时，将正文换成 `RequestBody.fromFile(Path.of("C:/temp/hello.txt"))`；已有字节数组可以使用 `RequestBody.fromBytes(bytes)`。

`contentType` 是调用者提供并保存的 MIME 元数据，RustFS 据此在读取时返回 `Content-Type`。业务需要识别真实图片格式时，可以读取文件内容并解码检查。

### 3.2 读取文件

`GetObjectRequest` 指定要读取的对象，返回方法决定内容以什么形式进入程序：

```java
var content = s3Client.getObjectAsBytes(GetObjectRequest.builder()
        .bucket(bucket).key(key).build());
String text = content.asUtf8String();
```

这里的 `text` 为 `Hello, RustFS`。二进制文件可以用 `content.asByteArray()` 获取字节；较大的文件适合用 `getObject` 的流或文件输出方式处理。

### 3.3 查询元数据

HEAD 请求只返回对象元数据，适合确认对象是否存在、查询大小和类型：

```java
var metadata = s3Client.headObject(request -> request.bucket(bucket).key(key));
long byteSize = metadata.contentLength();
String mimeType = metadata.contentType();
String etag = metadata.eTag();
```

`byteSize` 是存储对象的实际字节数，`mimeType` 是上传时保存的类型。ETag 是对象实体标签，其取值与存储实现和上传方式有关，业务可以把它当作对象版本比较信息使用。[S3 ETag 说明](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Object.html)

### 3.4 按前缀列出对象与删除对象

列举使用前缀筛选，分页器会按需获取后续页。下面查看 `notes/` 下的对象键：

```java
var pages = s3Client.listObjectsV2Paginator(request -> request
        .bucket(bucket).prefix("notes/"));
for (var page : pages) {
    for (var object : page.contents()) {
        System.out.println(object.key());
    }
}
```

删除使用桶名和对象键：

```java
s3Client.deleteObject(request -> request.bucket(bucket).key(key));
```

业务数据库中的文件记录与对象存储各自维护。调用以上方法完成存储操作后，由应用决定何时登记文件、关联业务对象或清理记录。

## 4. GET 与 PUT 预签名 URL

预签名 URL 把一次受限操作的授权放进地址：使用者在有效期内按约定的方法、请求头访问指定对象。应用先判断用户的业务权限，再决定签发哪一个对象的 URL。持有 URL 的客户端可以直接执行这项操作。

在 Service 中注入 `S3Presigner`，变量名为 `presigner`。以下使用 `java.time.Duration`，继续使用前面的 `bucket`。

### 4.1 GET 预签名：直接读取私有对象

```java
String readUrl = presigner.presignGetObject(builder -> builder
        .signatureDuration(Duration.ofMinutes(10))
        .getObjectRequest(request -> request
                .bucket(bucket).key("notes/hello.txt")))
        .url().toString();
```

把生成的地址放入浏览器即可读取文件。头像、图片展示等场景，可以由后端检查权限后将地址返回前端，前端直接用它加载资源。URL 到期后重新向后端申请。

### 4.2 PUT 预签名：客户端直接上传

签名器先确定对象键及本次上传使用的 MIME 类型，再生成上传凭据：

```java
String uploadKey = "images/" + UUID.randomUUID();
var upload = presigner.presignPutObject(builder -> builder
        .signatureDuration(Duration.ofMinutes(10))
        .putObjectRequest(request -> request
                .bucket(bucket).key(uploadKey).contentType("image/png")));
String uploadUrl = upload.url().toString();
```

生成地址只代表允许向这个对象键上传。客户端执行 PUT 并成功后，文件才会出现在 RustFS。`upload.signedHeaders()` 可以查看本次签名要求发送的请求头。

准备一张 PNG，在 PowerShell 将完整地址赋给 `$uploadUrl` 后执行：

```powershell
curl.exe -i -X PUT -H "Content-Type: image/png" --data-binary "@C:/temp/photo.png" $uploadUrl
```

`--data-binary` 将文件字节直接作为请求体。收到成功状态后，可以在控制台查看对应的 `images/…` 对象。

本例把 `Content-Type: image/png` 纳入签名，因此实际 PUT 使用相同值；上传 JPEG 时，申请与上传都改为 `image/jpeg`。主机、路径及签名参数也参与校验，客户端使用返回的完整 URL。上传申请里声明的大小只是业务输入；本例的签名没有绑定大小，上传后的实际字节数通过 HEAD 获取。[AWS 预签名示例](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

签名器的 Endpoint 要让使用 URL 的客户端能够访问：本机可用 `localhost`，手机可用局域网地址。应用运行在容器中时，S3Client 可以使用内部地址 `http://rustfs:9000`，S3Presigner 则配置为客户端可达的地址。

## 5. RustFS 上传事件发送到 RabbitMQ

上传事件用于告知其他系统“某个对象已经保存”。启用后，RustFS 负责发布事件，RabbitMQ 保存和路由消息，应用消费者负责处理业务，例如查询文件元数据并登记数据库。

配置分为两层：服务通知目标定义“发到哪个 MQ”，桶通知订阅定义“这个桶的哪些动作触发发送”。下面以 RustFS 1.0.4 的 AMQP 配置为例。[RustFS 事件通知](https://docs.rustfs.com/en/operations/event-notifications)

### 5.1 AMQP 发送目标

先按 [RabbitMQ 教程](RabbitMQ快速上手.md) 启动 RabbitMQ，并运行其中的资源声明与事件消费者。它会建立 `demo.events` 交换机、`demo.messages` 队列，以及 `message.created` 绑定键。

将两个服务放在同一个 Compose 文件中，在 RustFS 的 `environment` 追加：

```yaml
      RUSTFS_NOTIFY_ENABLE: "true"
      RUSTFS_NOTIFY_AMQP_ENABLE_DEMO: "on"
      RUSTFS_NOTIFY_AMQP_URL_DEMO: "amqp://demo:demo@rabbitmq:5672/%2f"
      RUSTFS_NOTIFY_AMQP_EXCHANGE_DEMO: "demo.events"
      RUSTFS_NOTIFY_AMQP_ROUTING_KEY_DEMO: "message.created"
      RUSTFS_NOTIFY_AMQP_PERSISTENT_DEMO: "on"
```

这里的 `rabbitmq` 是 Compose 服务名，两个容器通过内部网络连接。URL 中的 `%2f` 表示 RabbitMQ 默认虚拟主机 `/`。交换机和路由键与前面声明的绑定相配，持久化选项用于发送持久化消息。

后缀 `_DEMO` 定义一个名为 `demo` 的 AMQP 目标，下一步桶订阅通过 ARN 引用它。执行 `docker compose up -d rustfs` 更新容器，新的环境变量随容器重建生效；单纯 `restart` 会沿用原环境。[Compose restart 行为](https://docs.docker.com/reference/cli/docker/compose/restart/)

### 5.2 桶通知订阅

在第 2 节配置类增加下面的 Bean，为已创建的 `demo-files` 桶设置 PUT 上传事件：

```java
@Bean
public ApplicationRunner configureNotifications(S3Client s3Client) {
    return args -> s3Client.putBucketNotificationConfiguration(request -> request
            .bucket("demo-files")
            .notificationConfiguration(configuration -> configuration
                    .queueConfigurations(queue -> queue
                            .id("demo-upload")
                            .queueArn("arn:rustfs:sqs:us-east-1:demo:amqp")
                            .events(Event.S3_OBJECT_CREATED_PUT))));
}
```

`ApplicationRunner` 来自 `org.springframework.boot`，使配置在应用启动时执行；`Event` 来自 S3 SDK 的 `model` 包。ARN 中的 `demo:amqp` 指向上一步定义的目标，`S3_OBJECT_CREATED_PUT` 选择 PUT 创建事件。

SDK 沿用 S3 的通知配置结构，`queueConfigurations` 在这里引用 RustFS 的通知目标；RabbitMQ 队列由前面的 Spring 配置类声明。此 API 会替换整个桶的通知配置，多个订阅可以放在同一次配置中。

### 5.3 事件消费与文件登记

再次上传一个对象，应用会收到包含 `Records` 数组的 JSON。每条记录描述事件名、桶及对象键；完整消息模型和消费者代码见 [RabbitMQ 教程的 RustFS 示例](RabbitMQ快速上手.md#6-rustfs-上传事件的消息格式)。

业务处理顺序可以是：解析事件并解码对象键，调用 HEAD 获取实际元数据，按对象键找到归属及文件标识，再保存文件记录。消费者业务处理成功并返回后，Spring 向 RabbitMQ 确认消息。

PUT 成功表示对象已经保存，文件记录就绪还要等待事件消费。前端可以通过应用提供的文件状态接口查询后续结果。两者发生在不同阶段，因此状态接口也能帮助页面显示上传后的处理进度。

## 6. 连接、签名与事件问题定位

| 现象 | 检查与处理 |
| --- | --- |
| 连接被拒绝 | 查看容器状态，确认 Endpoint 使用 S3 端口 `9000` |
| NoSuchBucket | 在控制台创建代码使用的桶 |
| SignatureDoesNotMatch | 核对 Content-Type、完整签名 URL、凭据及系统时间 |
| 浏览器报告 CORS 错误 | 为前端来源和 PUT 请求配置跨域规则 |
| 上传成功却没有事件 | 检查实际容器环境、桶订阅 ARN 和 RustFS 日志 |
| MQ 队列数量为零 | 查看发布、投递及确认统计，消息可能已经处理完成 |

跨域配置参考 [RustFS CORS 文档](https://docs.rustfs.com/en/administration/cors)。项目中的文件 ID、访问校验和入库流程见 [后端开发约定](../YOU-后端开发约定.md)。
