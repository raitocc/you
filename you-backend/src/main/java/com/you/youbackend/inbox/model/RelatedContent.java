package com.you.youbackend.inbox.model;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.response.RelatedSourceResponse;
import com.you.youbackend.moment.entity.Moment;

/**
 * 已通过来源权限与内容有效性筛选的动态，保留已加载数据供摘要组装复用。
 * @param inbox 原信箱记录及分享时间
 * @param source 当前有效来源的类型、ID 和名称
 * @param moment 瞬间实体，时光动态为 null
 * @param album 时光实体，瞬间动态为 null
 */
public record RelatedContent(UserInbox inbox, RelatedSourceResponse source, Moment moment, Album album) {}
