package com.you.youbackend.media.consumer;

import com.you.youbackend.common.config.RabbitMqConfig;
import com.you.youbackend.media.event.MediaUploadEvent;
import com.you.youbackend.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 接收 RustFS 上传消息，交给媒体服务登记文件。
 */
@Component
@RequiredArgsConstructor
public class MediaUploadConsumer {

    private final ObjectMapper objectMapper;
    private final MediaService mediaService;

    /**
     * 解析上传事件；文件登记成功后，由监听容器确认消息。
     */
    @RabbitListener(queues = RabbitMqConfig.MEDIA_UPLOAD_QUEUE)
    public void consume(byte[] body) {
        MediaUploadEvent event = objectMapper.readValue(body, MediaUploadEvent.class);
        mediaService.handleUploadEvent(event);
    }
}
