# YOU 数据库设计

## 设计概述

YOU 是用户记录、整理和分享个人经历的平台。本设计以“瞬间”为基本内容单位，围绕时光、同行和圈子组织这些记录，并提供用户信箱和站内通知。当前本地运行库共 15 张业务表，覆盖下述基础业务范围。

- **瞬间**：用户发布的文字、图片或图文记录，原内容保存一份。
- **时光**：由用户手动选择或 AI 一次性整理形成的瞬间集合。
- **同行**：围绕一次共同经历组织成员和瞬间。
- **圈子**：由一群人组成的持续分享空间，可分享瞬间、时光和同行入口。
- **用户信箱**：接收相关空间的新内容动态。
- **站内通知**：接收与用户有关的互动或业务结果提示。

本版先支持基础账号、图片记录、一次性 AI 整理、成员与内容分享、站内消息。草稿保存在客户端本地；时光不自动持续维护；空间由创建者管理。点赞、评论尚未设计实际业务表，目前仅在通知类型中预留，因此不计入本版已覆盖的业务。本文用于说明表结构及主要业务关系，不表示相关功能已经实现。

本文面向后端开发与联调人员，以 2026-11-08 本地 MySQL 的 `you` 库元数据为准，结合当前后端代码说明使用方式。业务关系和共享规则属于设计约定，实际接口覆盖范围以实现为准。

### 数据库与迁移基线

实际库使用 MySQL 8.4、InnoDB 引擎和 `utf8mb4` 字符集，表默认排序规则为 `utf8mb4_0900_ai_ci`。`media_file.storage_key`、`media_upload.storage_key` 和 `moment_tag.tag_name` 单独使用 `utf8mb4_0900_bin`，按大小写精确匹配。

## 数据表汇总

| 序号 | 数据表名 | 中文名称 |
| ---- | -------- | -------- |
| 1 | app_user | 用户表 |
| 2 | media_file | 媒体文件表 |
| 3 | moment | 瞬间表 |
| 4 | moment_image | 瞬间图片表 |
| 5 | moment_tag | 瞬间标签表 |
| 6 | album | 时光表 |
| 7 | album_moment | 时光瞬间关联表 |
| 8 | gathering | 同行表 |
| 9 | gathering_member | 同行成员表 |
| 10 | circle | 圈子表 |
| 11 | circle_member | 圈子成员表 |
| 12 | circle_share | 圈子分享表 |
| 13 | user_inbox | 用户信箱表 |
| 14 | notification | 站内通知表 |
| 15 | media_upload | 媒体上传任务表 |

## 通用约定

1. **标识**：除上传任务表外，业务表主键约定使用 UUIDv7，数据库存储为 `BINARY(16)`；引用这些对象的 ID 使用相同类型。接口使用 UUID 字符串，应用通过 `UuidTypeHandler` 按 UUID 原始字节顺序完成转换。
2. **审计时间**：`created_at`、`updated_at` 为非空的 `DATETIME(3)`，由应用填写，数据库无自动填充默认值；按 UTC 写入和读取，界面按用户所需时区展示。`memory_time` 是经历发生时间，不用主键生成时间或发布时间代替。
3. **逻辑删除**：除 `media_upload` 外，业务表均有 `deleted_at`。`deleted_at` 可空，为空表示未删除；空间中表示解散，成员关系中表示离开或移除，分享关系中表示撤回。默认业务读取忽略已删除对象或无效关系。文件是否物理回收，还要确认没有其他有效引用。
4. **唯一关系**：已注明联合唯一的成员、集合及分享关系不重复建立。重新建立时恢复原行；恢复分享同时更新本次分享时间。联合唯一键本身不加入可空的 `deleted_at`。
5. **引用原内容**：加入时光、关联同行、分享到圈子均不复制瞬间。移出集合或撤销分享只改变关系；删除原瞬间后，各入口停止展示该内容。
6. **类型与 ID**：多态引用使用明确的类型及对象 ID，例如 `circle_share.object_type` 与 `object_id`；服务层按类型定位原表，并校验对应操作资格。普通 ID 引用本身不是查看凭证。
7. **已读**：`read_at` 为空表示未读，有值表示已读；读消息不改变成员、分享或原对象的业务状态。

数据库通过主键、非空和唯一索引约束基础数据；当前没有物理外键或 `CHECK` 约束。文中的“关联”均为逻辑引用。非空白文本、正数字节数、经纬度范围与成对填写、枚举取值、正文与图片至少有一种、图片有效顺序及访问资格等规则由应用层维护。

唯一索引覆盖逻辑删除行，账号、对象键、邀请码及联合唯一关系在逻辑删除后仍占用原唯一值。默认排序规则影响字符串比较，登录账号忽略大小写，标签名称按精确值区分。

## 浏览与共享规则

- **Yours**：查询本人发布且未删除的瞬间。
- **Related**：查询有效同行中的瞬间，以及有效圈子直接分享的瞬间；按瞬间 ID 去重，并保留当前有效来源。本人已经分享的瞬间可以同时出现在 Yours 和 Related。
- **搜索**：在上述两类可访问瞬间的并集中查找，不受当前浏览 Tab 限制。当前字段支持普通条件查询；自然语言理解和语义检索由相应服务及检索索引实现。
- **集合入口**：分享到圈子的时光仍按原瞬间权限展示内容；分享到圈子的同行，非同行成员只进入介绍及加入入口，确认加入后才建立同行资格。
- **内容与消息**：成员及分享关系决定当前访问范围；信箱、通知只是入口。新发布到同行或新分享到圈子可生成信箱动态，已有内容编辑不重复刷出新动态。成员变化、空间解散和 AI 完成等生成对应站内通知。

### 1. app_user

app_user 表为用户表，用于登录账号、密码散列、昵称和头像。

保存用户的登录账号、密码散列和个人资料，是瞬间作者、时光所有者、空间创建者及成员身份的统一来源。昵称可以重名，登录账号唯一。头像通过 `avatar_file_id` 引用媒体文件；本版账号模块只使用这一张表。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 用户 ID | 主键，使用 UUIDv7 |
| username | VARCHAR(64) | 登录账号 | 非空、唯一，比较时忽略大小写 |
| password_hash | VARCHAR(255) | 密码散列 | 非空 |
| nickname | VARCHAR(64) | 展示昵称 | 可空，允许重名 |
| avatar_file_id | BINARY(16) | 头像文件 ID | 可空，关联 media_file.id |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 2. media_file

media_file 表为媒体文件表，用于图片对象存储位置及文件元数据。

保存头像、瞬间组图和时光封面使用的图片元数据，文件内容存放在私有 RustFS 对象存储中。当前后端消费 RustFS 上传事件，检查对象元数据后建立文件记录；实际库还保留独立的 `media_upload` 上传任务表，见第 15 节。当前源码未发现该任务表的实体、Mapper 或读写逻辑。`storage_key` 用于定位文件，访问时根据实际引用和当前用户权限生成临时地址；临时地址不写入本表。文件有其他有效用途时，不因某一处引用移除而直接清理文件。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 文件 ID | 主键，使用 UUIDv7 |
| owner_user_id | BINARY(16) | 上传者 ID | 非空，关联 app_user.id |
| storage_key | VARCHAR(512) | RustFS 对象键 | 非空、唯一 |
| mime_type | VARCHAR(64) | 实际文件类型 | 例如 image/jpeg，非空 |
| byte_size | BIGINT | 文件字节数 | 非空、正整数 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 3. moment

moment 表为瞬间表，用于原始图文内容、作者、心情、发生时间、地点及同行关联。

保存一条生活记录的原始内容，一条瞬间只有一个作者。标题可选，正文和图片至少有一种；图片和标签分别存放在对应关联表。`memory_time` 表示经历发生的时间，与发布时的 `created_at` 分开。可空的 `gathering_id` 表示当前关联同行，从结构上限制一条瞬间最多属于一个同行；分享到多个圈子则通过 `circle_share` 表达。各入口读取同一份瞬间，不复制原始图文。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 瞬间 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | 即发布时间，UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| author_user_id | BINARY(16) | 作者 ID | 非空，关联 app_user.id |
| gathering_id | BINARY(16) | 当前关联的同行 ID | 可空，关联 gathering.id |
| title | VARCHAR(255) | 标题 | 可空 |
| body | TEXT | 普通文本正文 | 可空；去除空白后非空或至少有一张图片 |
| mood | VARCHAR(64) | 心情 | 可空 |
| memory_time | DATETIME(3) | 瞬间发生时间 | 非空，UTC |
| latitude | DECIMAL(10,7) | 纬度 | 可空，范围 -90 至 90，与经度同时为空或同时有值 |
| longitude | DECIMAL(10,7) | 经度 | 可空，范围 -180 至 180，与纬度同时为空或同时有值 |
| location_name | VARCHAR(255) | 地点名称 | 可空 |
| location_address | VARCHAR(512) | 地点地址 | 可空 |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 4. moment_image

moment_image 表为瞬间图片表，用于瞬间与图片文件的关系及展示顺序。

记录瞬间使用了哪些媒体文件，以及组图展示顺序。一个瞬间可以包含多张图片，每条记录引用一个 `media_file`。同一对瞬间与文件仅保留一条关系，移除后重新添加时恢复原行。调整图片顺序或移除图片只改变瞬间与文件的关系，不直接删除被其他有效用途引用的文件。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 图片关联 ID | 主键，使用 UUIDv7 |
| moment_id | BINARY(16) | 瞬间 ID | 非空，关联 moment.id |
| file_id | BINARY(16) | 图片文件 ID | 非空，关联 media_file.id |
| sort_order | INT | 图片展示顺序 | 非空，从 1 开始；有效图片顺序由应用维护，数据库未建顺序唯一约束 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 5. moment_tag

moment_tag 表为瞬间标签表，用于瞬间的标签名称。

直接保存瞬间的标签名称，一条瞬间可以有多条标签记录，同一瞬间内标签名称不重复。本版不建立独立标签字典；按标签查询时从本表定位对应瞬间。删除某条标签记录只影响该瞬间，不会修改其他瞬间中的同名标签。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 标签记录 ID | 主键，使用 UUIDv7 |
| moment_id | BINARY(16) | 瞬间 ID | 非空，关联 moment.id |
| tag_name | VARCHAR(64) | 标签名称 | 非空、非空白，同一瞬间内按大小写精确匹配且不可重复 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 6. album

album 表为时光表，用于集合信息、整理方式、AI 整理要求及封面。

保存用户建立的瞬间集合及其名称、介绍和封面。`manual` 表示手动选择瞬间创建，`ai` 表示由 AI 按指定要求整理；`organize_prompt` 记录该次整理要求，不作为持续执行的规则。AI 每次只整理确认的范围，例如一月份的记录；后续新增瞬间不自动加入，用户可以手动调整集合，或再次执行整理。删除时光保留其中的原始瞬间。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 时光 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| owner_user_id | BINARY(16) | 所有者 ID | 非空，关联 app_user.id |
| name | VARCHAR(128) | 时光名称 | 非空、非空白，允许重名 |
| description | TEXT | 时光介绍 | 可空 |
| organize_mode | VARCHAR(16) | 整理方式 | 非空，默认 manual；manual 手动、ai AI 整理 |
| organize_prompt | TEXT | AI 整理要求 | 可空，例如“整理一月份的旅行瞬间” |
| cover_file_id | BINARY(16) | 封面文件 ID | 可空，关联 media_file.id |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 7. album_moment

album_moment 表为时光瞬间关联表，用于时光所包含的瞬间。

记录一个时光包含哪些瞬间。时光与瞬间是多对多关系：一个时光可包含多条瞬间，同一瞬间也可属于多个时光。同一对时光和瞬间仅保留一条关系；移出后再次加入时恢复原关系。时光线按原瞬间发生时间及 ID 排列，不在本表重复保存图文、时间或人工排序字段。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 关联 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| album_id | BINARY(16) | 时光 ID | 非空，关联 album.id，与 moment_id 联合唯一 |
| moment_id | BINARY(16) | 瞬间 ID | 非空，关联 moment.id |
| deleted_at | DATETIME(3) | 移出时间 | UTC；为空表示关联有效 |

### 8. gathering

gathering 表为同行表，用于共同经历的信息、创建者、阶段及邀请码。

保存围绕一次共同经历建立的记录空间。创建者负责编辑信息、结束和解散等管理操作，本版不设置独立管理员，也不支持管理权转交。进行中和已结束均允许加入与补记，解散通过 `deleted_at` 表达。`invite_code` 是长期加入凭证，二维码是该凭证的展示方式；关闭邀请或重置旧码后，旧入口不能继续用于加入，已加入成员不受影响。同行中的瞬间通过 `moment.gathering_id` 查询。

面对面邀请每个同行只允许成功开启一次，使用四位数字短码，由创建者在同行创建后 10 分钟内开启，截止时间固定为创建时间加 10 分钟。`face_code` 永久记录曾开启过邀请；Redis GEO 按短码和十分钟时间段保存发起位置。附近预览按 100 米范围发现候选，确认加入仅凭同行 ID 与短码，按数据库短码、有效期、邀请开关及解散状态校验。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 同行 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| creator_user_id | BINARY(16) | 创建者 ID | 非空，关联 app_user.id；创建者负责同行管理操作 |
| name | VARCHAR(128) | 同行名称 | 非空、非空白，允许重名 |
| description | TEXT | 同行介绍 | 可空 |
| status | TINYINT | 同行状态 | 非空，默认 1；1 进行中、2 已结束 |
| invite_enabled | TINYINT | 是否允许通过同行码加入 | 非空，0 否、1 是，默认 1 |
| invite_code | VARCHAR(16) | 同行邀请码 | 非空、唯一，由系统随机生成，可重置 |
| face_code | CHAR(4) | 首次面对面短码 | 可空，保留前导零，允许不同同行同码；成功开启后永久保留 |
| deleted_at | DATETIME(3) | 解散时间 | UTC；为空表示未解散 |

### 9. gathering_member

gathering_member 表为同行成员表，用于同行成员资格。

保存用户与同行的成员关系，创建同行时同时建立创建者的成员关系。同一用户在同一同行只保留一条记录，退出或移除通过 `deleted_at` 标记，再次加入恢复原关系。退出或被移除时，取消该用户瞬间与当前同行的关联，保留原瞬间；创建者暂不直接退出自己创建的同行，可选择解散。从圈子中选择成员创建同行时，直接建立所选成员的关系，不经过定向邀请确认；加入同行不会自动关联成员原有的瞬间。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 成员关系 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| gathering_id | BINARY(16) | 同行 ID | 非空，关联 gathering.id，与 user_id 联合唯一 |
| user_id | BINARY(16) | 成员 ID | 非空，关联 app_user.id |
| deleted_at | DATETIME(3) | 离开或被移除的时间 | UTC；为空表示成员关系有效 |

### 10. circle

circle 表为圈子表，用于持续分享空间的信息、创建者及邀请码。

保存围绕一群人建立的持续分享空间。创建者负责圈子管理，本版不设置独立管理员；成员资格通过 `circle_member` 维护，内容通过 `circle_share` 维护。圈子邀请码和邀请开关用于控制加入入口。圈子没有进行中、已结束的阶段区别，解散只记录 `deleted_at`，保留原瞬间、时光及独立同行。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 圈子 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| creator_user_id | BINARY(16) | 创建者 ID | 非空，关联 app_user.id；创建者负责圈子管理操作 |
| name | VARCHAR(128) | 圈子名称 | 非空、非空白，允许重名 |
| description | TEXT | 圈子介绍 | 可空 |
| invite_enabled | TINYINT | 是否允许通过圈子码加入 | 非空，0 否、1 是，默认 1 |
| invite_code | VARCHAR(16) | 圈子邀请码 | 非空、唯一，由系统随机生成，可重置 |
| deleted_at | DATETIME(3) | 解散时间 | UTC；为空表示未解散 |

### 11. circle_member

circle_member 表为圈子成员表，用于圈子成员资格。

保存用户与圈子的成员关系，创建圈子时同时建立创建者的成员关系。同一用户在同一圈子只保留一条记录，离开或被移除时标记 `deleted_at`，再次加入恢复原关系，但不自动恢复旧分享。退出或移除时撤回该用户在圈子中的分享；创建者暂不直接退出自己创建的圈子，可选择解散。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 成员关系 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| circle_id | BINARY(16) | 圈子 ID | 非空，关联 circle.id，与 user_id 联合唯一 |
| user_id | BINARY(16) | 成员 ID | 非空，关联 app_user.id |
| deleted_at | DATETIME(3) | 离开或被移除的时间 | UTC；为空表示成员关系有效 |

### 12. circle_share

circle_share 表为圈子分享表，用于三种内容在圈子中的分享关系。

统一记录瞬间、时光和同行在圈子中的分享入口，使用 `object_type` 与 `object_id` 定位原对象，不复制原内容。`sharer_user_id` 表示本次分享者，`shared_at` 用于排序；编辑原对象不改变分享时间。同一圈子内同一种对象只保留一条关系，撤回后重新分享恢复原行并更新分享者和分享时间。时光分享不自动授予内部瞬间权限；同行分享也不自动建立同行成员关系。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 分享关系 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| circle_id | BINARY(16) | 目标圈子 ID | 非空，关联 circle.id；与 object_type、object_id 联合唯一 |
| object_type | VARCHAR(16) | 分享对象类型 | 非空，moment 瞬间、album 时光、gathering 同行 |
| object_id | BINARY(16) | 原对象 ID | 非空，与对象类型对应 |
| sharer_user_id | BINARY(16) | 本次分享者 ID | 非空，关联 app_user.id |
| shared_at | DATETIME(3) | 本次分享时间 | UTC，用于圈子内容排序；重新分享时更新，编辑原对象不改变 |
| deleted_at | DATETIME(3) | 撤回或移除时间 | UTC；为空表示分享关系未撤销 |

### 13. user_inbox

user_inbox 表为用户信箱表，用于来自相关空间的新内容动态及已读状态。

保存相关同行、圈子中的新内容动态，例如同行新增瞬间、圈子分享时光。每条记录属于一个接收者，`source_type` 与 `source_id` 表示来源空间，`object_type` 与 `object_id` 表示打开的内容，两者可以分别导航。本表只保存动态入口，不复制图文，也不代替成员或分享关系。`read_at` 记录用户是否读过；查看动态及打开内容时仍按当前空间和原对象权限判断。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 信箱动态 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| recipient_user_id | BINARY(16) | 接收者 ID | 非空，关联 app_user.id |
| actor_user_id | BINARY(16) | 发布或分享内容的用户 ID | 非空，关联 app_user.id |
| source_type | VARCHAR(16) | 来源空间类型 | 非空，gathering 同行、circle 圈子 |
| source_id | BINARY(16) | 来源同行或圈子的 ID | 非空 |
| object_type | VARCHAR(16) | 内容类型 | 非空，moment 瞬间、album 时光、gathering 同行 |
| object_id | BINARY(16) | 对应内容 ID | 非空 |
| read_at | DATETIME(3) | 已读时间 | UTC；为空表示未读 |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 14. notification

notification 表为站内通知表，用于用户相关通知及已读状态。

保存与用户有关的互动或业务结果，例如被加入同行、被移除、空间解散或 AI 整理完成。用户触发的通知记录触发者，系统通知允许触发者为空；无具体目标时，目标类型和 ID 同时为空。`content` 保存简短通知文案，`read_at` 表示是否已读。点赞和评论类型仅为通知表达预留，不代表本版已具备点赞、评论业务存储。来源失效时可保留必要结果提示，打开关联内容仍检查当前权限。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 通知 ID | 主键，使用 UUIDv7 |
| created_at | DATETIME(3) | 创建时间 | UTC |
| updated_at | DATETIME(3) | 最后修改时间 | UTC |
| recipient_user_id | BINARY(16) | 接收者 ID | 非空，关联 app_user.id |
| actor_user_id | BINARY(16) | 触发通知的用户 ID | 关联 app_user.id；系统通知可空 |
| notification_type | VARCHAR(32) | 通知类型 | 非空，例如 like、comment、member_added、removed、dissolved、ai_completed |
| target_type | VARCHAR(16) | 关联对象类型 | moment、album、gathering、circle；无关联对象时可空 |
| target_id | BINARY(16) | 关联对象 ID | 可空，与 target_type 同时为空或同时有值 |
| content | VARCHAR(255) | 简短通知文案 | 非空 |
| read_at | DATETIME(3) | 已读时间 | UTC；为空表示未读 |
| deleted_at | DATETIME(3) | 删除时间 | UTC；为空表示未删除 |

### 15. media_upload

`media_upload` 是实际库中的媒体上传任务表，记录上传者、目标对象键、文件信息、过期与保留时间，以及完成后的文件引用。字段用途根据命名说明；当前仓库没有该表的业务读写实现，任务创建、回填和清理行为无法从当前代码确认。

该表只有 `created_at`，没有 `updated_at` 或 `deleted_at`；不能直接套用其他业务表的审计和逻辑删除规则。数据库没有约束 `file_id`、`completed_at` 必须同时填写。

| 字段名 | 数据类型 | 说明 | 备注 |
| ------ | -------- | ---- | ---- |
| id | BINARY(16) | 上传任务 ID | 主键；数据库仅约束字节长度，生成方式待实现确认 |
| owner_user_id | BINARY(16) | 上传者 ID | 非空，逻辑关联 app_user.id |
| storage_key | VARCHAR(512) | RustFS 对象键 | 非空，区分大小写；没有唯一索引 |
| mime_type | VARCHAR(64) | 文件类型 | 非空 |
| byte_size | BIGINT | 文件字节数 | 非空 |
| expires_at | DATETIME(3) | 上传过期时间 | 非空，按项目约定使用 UTC |
| retain_until | DATETIME(3) | 任务保留截止时间 | 非空 |
| file_id | BINARY(16) | 完成后的文件 ID | 可空，逻辑关联 media_file.id |
| completed_at | DATETIME(3) | 上传完成时间 | 可空 |
| created_at | DATETIME(3) | 创建时间 | 非空，无数据库默认值 |

## 索引与查询入口

以下索引来自当前运行库，字段顺序与数据库一致。15 张业务表均以 `id` 为主键，下表列出主键以外的全部索引。“唯一”表示数据库强制去重；普通索引用于定位和排序。

| 数据表 | 索引名 | 类型 | 字段（按索引顺序） |
| ------ | ------ | ---- | ------------------ |
| album | idx_album_owner_list | 普通 | owner_user_id, deleted_at, created_at, id |
| album_moment | idx_album_moment_moment | 普通 | moment_id |
| album_moment | uk_album_moment_album_moment | 唯一 | album_id, moment_id |
| app_user | uk_app_user_username | 唯一 | username |
| circle | uk_circle_invite_code | 唯一 | invite_code |
| circle_member | idx_circle_member_user | 普通 | user_id, deleted_at, circle_id |
| circle_member | uk_circle_member_circle_user | 唯一 | circle_id, user_id |
| circle_share | idx_circle_share_feed | 普通 | circle_id, deleted_at, shared_at, id |
| circle_share | idx_circle_share_object | 普通 | object_type, object_id, deleted_at, circle_id |
| circle_share | idx_circle_share_sharer | 普通 | circle_id, sharer_user_id, deleted_at |
| circle_share | uk_circle_share_circle_object | 唯一 | circle_id, object_type, object_id |
| gathering | uk_gathering_invite_code | 唯一 | invite_code |
| gathering_member | idx_gathering_member_user | 普通 | user_id, deleted_at, gathering_id |
| gathering_member | uk_gathering_member_gathering_user | 唯一 | gathering_id, user_id |
| media_file | idx_media_file_owner | 普通 | owner_user_id, deleted_at |
| media_file | uk_media_file_storage_key | 唯一 | storage_key |
| media_upload | idx_media_upload_owner | 普通 | owner_user_id |
| media_upload | idx_media_upload_retention | 普通 | retain_until |
| moment | idx_moment_author_feed | 普通 | author_user_id, deleted_at, memory_time, id |
| moment | idx_moment_gathering_feed | 普通 | gathering_id, deleted_at, memory_time, id |
| moment_image | idx_moment_image_file | 普通 | file_id, deleted_at |
| moment_image | idx_moment_image_order | 普通 | moment_id, deleted_at, sort_order |
| moment_image | uk_moment_image_moment_file | 唯一 | moment_id, file_id |
| moment_tag | idx_moment_tag_name | 普通 | tag_name, deleted_at, moment_id |
| moment_tag | uk_moment_tag_moment_name | 唯一 | moment_id, tag_name |
| notification | idx_notification_recipient_feed | 普通 | recipient_user_id, deleted_at, created_at, id |
| notification | idx_notification_unread | 普通 | recipient_user_id, deleted_at, read_at |
| user_inbox | idx_user_inbox_recipient_feed | 普通 | recipient_user_id, deleted_at, created_at, id |
| user_inbox | idx_user_inbox_unread | 普通 | recipient_user_id, deleted_at, read_at |

瞬间列表索引支持作者和同行维度的时间线；时光列表按所有者定位。圈子分享提供圈子动态、原对象反查和分享者筛选索引。信箱与通知分别提供接收者动态和未读查询索引。lbum_moment 的联合唯一索引可按 lbum_id 定位集合内容，独立的 moment_id 索引用于反查所属时光。
