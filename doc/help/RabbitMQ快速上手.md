# RabbitMQ：消息模型、发送、消费与 RustFS 事件

本文介绍 RabbitMQ 的基本模型，以及 Spring Boot 如何声明资源、发送和消费消息，最后接收 RustFS 的上传事件。示例基于已有的 Java 17、Spring Boot 4 Web MVC 项目，省略常规 import。

## 1. RabbitMQ 启动与连接

在 `compose.yaml` 中添加 RabbitMQ：

```yaml
services:
  rabbitmq:
    image: rabbitmq:4-management
    environment:
      RABBITMQ_DEFAULT_USER: demo
      RABBITMQ_DEFAULT_PASS: demo
    ports:
      - "127.0.0.1:5672:5672"
      - "127.0.0.1:15672:15672"
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
volumes:
  rabbitmq_data:
```

执行 `docker compose up -d rabbitmq`。管理页面是 `http://localhost:15672`，账号密码均为 `demo`。`5672` 是应用收发消息的 AMQP 端口，`15672` 是管理页面端口。

在 `pom.xml` 添加 `org.springframework.boot:spring-boot-starter-amqp`，版本由 Spring Boot 管理。随后在 `application.yaml` 配置连接：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: demo
    password: demo
```

Spring Boot 会配置发送消息使用的 `RabbitTemplate`、声明资源使用的 `RabbitAdmin`，以及运行消费者的监听容器。后面的示例直接注入使用。[Spring Boot AMQP 接入](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)

## 2. 交换机、绑定、队列与消费者

一条消息经过以下过程：

```text
生产者 → 交换机 → 根据绑定规则路由 → 队列 → 消费者
```

生产者负责发送消息，例如业务 Service 或 RustFS。消息包括正文和属性：正文可以是文本、JSON 或其他字节数据，属性可以描述内容类型等信息。

交换机（Exchange）接收生产者发布的消息，按照绑定（Binding）将消息路由到队列（Queue）。队列保存等待处理的消息，消费者从队列接收消息并执行业务。因此，生产者发送时指定交换机和路由键，消费者监听时指定队列。

路由键（routing key）描述这次消息如何路由，绑定键描述队列接收哪些消息。它们属于路由信息，与 JSON 正文里的字段各自独立。

| 交换机类型 | 路由方式 | 示例用途 |
| --- | --- | --- |
| Direct | 路由键与绑定键精确匹配 | 把上传事件交给文件处理队列 |
| Topic | 按点分隔的路由键匹配，支持 `*` 和 `#` | 按业务类别订阅事件 |
| Fanout | 发给所有已绑定队列 | 多个业务各接收一份广播 |

下面使用 Direct：交换机叫 `demo.events`，队列叫 `demo.messages`，绑定键为 `message.created`。生产者使用同样的路由键发布，消息就会进入这个队列。[RabbitMQ 交换机说明](https://www.rabbitmq.com/docs/exchanges)

## 3. 使用配置类声明消息资源

创建 `MessagingConfig`，把交换机、队列和绑定声明为 Spring Bean：

```java
@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = "demo.events";
    public static final String QUEUE = "demo.messages";
    public static final String ROUTING_KEY = "message.created";

    @Bean
    public DirectExchange demoExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue demoQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding demoBinding(DirectExchange demoExchange, Queue demoQueue) {
        return BindingBuilder.bind(demoQueue).to(demoExchange).with(ROUTING_KEY);
    }
}
```

这些类型来自 `org.springframework.amqp.core`。`DirectExchange` 的两个布尔参数分别表示持久化、自动删除；`Queue` 的 `true` 表示队列持久化。持久化资源可以在 RabbitMQ 重启后保留，消息本身的持久化由消息属性决定。

绑定代码表达的是“把这个队列绑定到这个交换机，使用这个绑定键”。Spring 建立连接后，`RabbitAdmin` 将这些声明发送给 RabbitMQ；已有资源会校验属性，首次声明则创建资源。后面启动监听器便会建立连接。[Spring AMQP 资源声明](https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html)

## 4. 使用 RabbitTemplate 投递消息

创建发送服务，通过构造器注入 `RabbitTemplate`：

```java
@Service
public class MessagePublisher {
    private final RabbitTemplate rabbitTemplate;

    public MessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String text) {
        rabbitTemplate.convertAndSend(
                MessagingConfig.EXCHANGE, MessagingConfig.ROUTING_KEY, text);
    }
}
```

`convertAndSend` 的三个参数分别是交换机名、路由键和消息正文。这里的字符串会转换成消息字节；调用 `publish("HelloRabbitMQ")` 就会发布一条文本消息。可以从已有 Controller、Service 或测试入口调用它。

业务对象也可以先序列化为 JSON 字符串再发送，消费者按约定解析 JSON。交换机根据路由键投递，正文的结构由生产者与消费者约定。

发送与消费是两个独立阶段。调用发送方法后，消息由 RabbitMQ 路由和保存，消费者随后处理。需要确认代理已接收消息时，进一步使用发布确认（publisher confirms）；消费完成则由下一节的消费确认表示。[发布确认与消费确认](https://www.rabbitmq.com/docs/confirms)

## 5. 使用 @RabbitListener 消费消息

创建消费者，监听前面声明的队列：

```java
@Component
public class MessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    @RabbitListener(queues = MessagingConfig.QUEUE)
    public void receive(String text) {
        log.info("收到消息，text={}", text);
    }
}
```

`Logger` 和 `LoggerFactory` 来自 `org.slf4j`。应用启动后，Spring 为 `@RabbitListener` 方法建立监听容器，持续接收队列消息；每次收到消息便调用 `receive`。发布上一节的文本后，日志应出现 `收到消息，text=HelloRabbitMQ`。

默认的 AUTO 确认模式由 Spring 管理：方法正常返回后确认消息，RabbitMQ 随后将其从待处理记录中移除；普通业务异常向外抛出时，默认重新入队。修改数据库的消费者可以调用带事务的 Service，等事务提交后再返回。重复投递可能发生，业务唯一标识可以帮助识别已经处理的事件。[监听器异常处理](https://docs.spring.io/spring-boot/reference/messaging/amqp.html#messaging.amqp.receiving)

同一队列的多个消费者分担消息；两个业务各需要一份事件时，分别建队列并绑定到交换机。例如文件入库与图片分析各使用自己的队列，就能分别消费同一次上传事件。

## 6. RustFS 上传事件的消息格式

RustFS 可以直接充当生产者，把上传事件发送到 RabbitMQ。按 [RustFS 教程的事件配置](RustFS快速上手.md#5-rustfs-上传事件发送到-rabbitmq) 设置目标和桶订阅后，链路为：

```text
客户端 PUT → RustFS 保存文件 → 发布事件 → RabbitMQ → Spring 消费者
```

事件正文是 RustFS 生成的 JSON。RabbitMQ 保存并投递这段正文，Spring 接收后再解析成 Java 对象。下面保留消费所需的字段：

```json
{
  "Records": [{
    "eventName": "s3:ObjectCreated:Put",
    "s3": {
      "bucket": {"name": "demo-files"},
      "object": {"key": "images/example.png"}
    }
  }]
}
```

`Records` 是事件记录数组，`eventName` 是动作，`bucket.name` 和 `object.key` 定位对象。完整事件还包含其他元数据，Java 模型可以只声明当前业务需要的字段。

创建 `StorageEvent`。`@JsonProperty` 对应 JSON 中大写的 `Records`；`@JsonIgnoreProperties` 允许各层对象保留额外字段：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageEvent(@JsonProperty("Records") List<Entry> records) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String eventName, S3Info s3) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3Info(BucketInfo bucket, ObjectInfo object) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketInfo(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectInfo(String key) {}
}
```

这两个注解来自 `com.fasterxml.jackson.annotation`。模型中的层级与 JSON 一致，消费代码便可以通过明确类型读取字段。

## 7. RustFS 事件解析与业务处理

用下面的消费者替换第 5 节的文本消费者，继续监听同一个示例队列。`Message` 来自 `org.springframework.amqp.core`，可取得消息原始字节；`ObjectMapper` 在 Spring Boot 4 中使用 `tools.jackson.databind.ObjectMapper`，由构造器注入。

```java
@Component
public class StorageEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(StorageEventConsumer.class);
    private final ObjectMapper objectMapper;

    public StorageEventConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = MessagingConfig.QUEUE)
    public void receive(Message message) {
        StorageEvent event = objectMapper.readValue(message.getBody(), StorageEvent.class);
        for (StorageEvent.Entry entry : event.records()) {
            String bucket = entry.s3().bucket().name();
            String key = URLDecoder.decode(
                    entry.s3().object().key(), StandardCharsets.UTF_8);
            log.info("收到存储事件，event={}, bucket={}, key={}",
                    entry.eventName(), bucket, key);
        }
    }
}
```

对象键在事件中可能经过 URL 编码，解码后才能用于查询文件。上传一次后，应看到事件名、桶名和对象键的日志。

实际文件登记可以接在解析之后：调用文件 Service，选择目标桶及上传事件，通过 S3 HEAD 查询文件的实际大小、MIME 元数据等，再保存业务文件记录。事件名的具体形式以收到的消息为准，RustFS 可能使用 `s3:ObjectCreated:Put` 或 `ObjectCreated:Put`。同一对象重复通知时，按对象键或文件 ID 识别已有记录。

筛选可以分三层完成：RustFS 桶订阅选择事件类型及对象键前后缀；RabbitMQ 绑定按路由键选择队列；消费者再按 JSON 内的桶、事件名等业务字段处理。普通 Direct/Topic 路由只使用路由键，正文筛选放在消费代码中。

## 8. 管理页面与消息处理结果

在管理页面打开 `demo.messages`，查看消息数量、消费者和速率。消息处理很快时，队列数量可以持续为零，发布、投递和确认统计仍会增加。

| 现象 | 检查位置 |
| --- | --- |
| 已发布但队列没有收到 | 交换机、绑定键与生产者路由键 |
| 消息积压 | 消费者数量、应用连接与业务日志 |
| 同一消息反复出现 | 解析或业务异常导致重新投递 |
| 同名资源声明失败 | 已有资源的类型、持久化及参数 |

项目中的实际资源名称和消费入口见 [后端开发约定](../YOU-后端开发约定.md)。
