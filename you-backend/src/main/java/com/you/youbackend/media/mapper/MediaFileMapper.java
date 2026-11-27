package com.you.youbackend.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.media.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 媒体文件元数据的数据访问。
 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
    /**
     * 批量读取有效文件的对象键，不校验归属、不过滤类型，调用方已确认业务访问资格。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param fileIds 非空的文件 ID 集合
     * @return 只填充 id、storageKey 的投影，失效项不出现
     */
    default List<MediaFile> selectStorageKeys(Collection<UUID> fileIds) {
        // 为已授权的展示场景批量准备签名，文件 ID 本身不授予业务访问资格。
        return selectList(Wrappers.<MediaFile>lambdaQuery()
                .select(MediaFile::getId, MediaFile::getStorageKey)
                .in(MediaFile::getId, fileIds));
    }

    /**
     * 批量读取指定用户有效图片的对象键：按归属和 MIME 白名单过滤，失效项不出现、不报错。
     * 写入校验拿它比对数量，业务侧也可靠它取对象键，一处查询两用。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param userId    文件所属用户 ID
     * @param fileIds   非空的文件 ID 集合
     * @param mimeTypes 当前支持的图片 MIME 白名单
     * @return 只填充 id、storageKey 的投影，非本人、失效及非图片文件不出现
     */
    default List<MediaFile> selectOwnedStorageKeys(UUID userId, Collection<UUID> fileIds,
                                                  Collection<String> mimeTypes) {
        // 归属和类型都由本查询兜底，调用方只需比对返回数量。
        return selectList(Wrappers.<MediaFile>lambdaQuery()
                .select(MediaFile::getId, MediaFile::getStorageKey)
                .in(MediaFile::getId, fileIds)
                .eq(MediaFile::getOwnerUserId, userId)
                .in(MediaFile::getMimeType, mimeTypes));
    }

    /**
     * 判断指定用户的有效图片记录是否存在，口径与 validateOwnedImages 的写入校验一致：存在、未删除、属于该用户、MIME 在白名单内才算就绪。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param fileId    文件 ID
     * @param userId    文件所属用户 ID
     * @param mimeTypes 允许的图片 MIME 类型，调用方必须保证非空
     * @return 四项全部满足时返回 true，否则返回 false
     */
    default boolean existsOwnedFile(UUID fileId, UUID userId, Collection<String> mimeTypes) {
        // 就绪判断和读取校验走同一口径，避免“查得到却用不了”的口径分裂。
        return selectCount(Wrappers.<MediaFile>lambdaQuery()
                .eq(MediaFile::getId, fileId)
                .eq(MediaFile::getOwnerUserId, userId)
                .in(MediaFile::getMimeType, mimeTypes)) > 0;
    }
}
