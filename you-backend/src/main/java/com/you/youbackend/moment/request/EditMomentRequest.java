package com.you.youbackend.moment.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.you.youbackend.moment.model.MomentLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 瞬间可编辑字段，记录 JSON 中实际提供的字段以区分省略和显式清空。
 */
@Getter
@Schema(description = "编辑瞬间请求")
public class EditMomentRequest {

    @Getter(AccessLevel.NONE)
    private final Set<String> supplied = new HashSet<>();

    @Schema(description = "标题")
    @Size(max = 255)
    private String title;

    @Schema(description = "普通文本正文")
    private String body;

    @Schema(description = "有序图片文件 ID")
    @Size(max = 20)
    private List<@NotNull UUID> imageFileIds;

    @Schema(description = "标签名称")
    @Size(max = 20)
    private List<@NotNull String> tags;

    @Schema(description = "心情")
    @Pattern(regexp = "开心|平静|松弛|心动|惊喜|自在|低落")
    private String mood;

    @Schema(description = "带时区的发生时间")
    private OffsetDateTime memoryTime;

    @Schema(description = "发生地点")
    @Valid
    private MomentLocation location;

    @Schema(description = "关联同行 ID")
    private UUID journeyId;

    @Schema(description = "分享圈子 ID")
    @Size(max = 100)
    private List<@NotNull UUID> circleIds;

    /** 判断指定字段是否由客户端提供。 */
    public boolean contains(String field) {
        return supplied.contains(field);
    }

    /** 判断客户端是否提供了可编辑字段。 */
    public boolean hasChanges() {
        return !supplied.isEmpty();
    }

    @JsonSetter
    public void setTitle(String title) {
        this.title = title;
        supplied.add("title");
    }

    @JsonSetter
    public void setBody(String body) {
        this.body = body;
        supplied.add("body");
    }

    @JsonSetter
    public void setImageFileIds(List<@NotNull UUID> imageFileIds) {
        this.imageFileIds = imageFileIds;
        supplied.add("imageFileIds");
    }

    @JsonSetter
    public void setTags(List<@NotNull String> tags) {
        this.tags = tags;
        supplied.add("tags");
    }

    @JsonSetter
    public void setMood(String mood) {
        this.mood = mood;
        supplied.add("mood");
    }

    @JsonSetter
    public void setMemoryTime(OffsetDateTime memoryTime) {
        this.memoryTime = memoryTime;
        supplied.add("memoryTime");
    }

    @JsonSetter
    public void setLocation(MomentLocation location) {
        this.location = location;
        supplied.add("location");
    }

    @JsonSetter
    public void setJourneyId(UUID journeyId) {
        this.journeyId = journeyId;
        supplied.add("journeyId");
    }

    @JsonSetter
    public void setCircleIds(List<@NotNull UUID> circleIds) {
        this.circleIds = circleIds;
        supplied.add("circleIds");
    }
}
