package com.you.youbackend.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件上传事件的交换机、队列与路由绑定。
 */
@Configuration
public class RabbitMqConfig {

    public static final String STORAGE_EXCHANGE = "you.storage.events";
    public static final String MEDIA_UPLOAD_QUEUE = "you.media.uploaded";
    public static final String MEDIA_UPLOAD_ROUTING_KEY = "media.uploaded";

    /**
     * 按 routing key 精确匹配的持久化交换机。
     */
    @Bean
    public DirectExchange storageExchange() {
        return new DirectExchange(STORAGE_EXCHANGE, true, false);
    }

    /**
     * 保存待处理文件上传事件的持久化队列。
     */
    @Bean
    public Queue mediaUploadQueue() {
        return new Queue(MEDIA_UPLOAD_QUEUE, true);
    }

    /**
     * 将上传事件路由到文件处理队列。
     */
    @Bean
    public Binding mediaUploadBinding(
            DirectExchange storageExchange,
            Queue mediaUploadQueue) {

        return BindingBuilder.bind(mediaUploadQueue)
                .to(storageExchange)
                .with(MEDIA_UPLOAD_ROUTING_KEY);
    }
}