package com.you.youbackend.media.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RustFS 对象事件通知，只接收文件登记所需字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaUploadEvent(
        @JsonProperty("Records")
        List<EventRecord> records
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventRecord(
            String eventName,
            S3Info s3
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3Info(
            BucketInfo bucket,
            ObjectInfo object
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketInfo(
            String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectInfo(
            String key
    ) {
    }
}