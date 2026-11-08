# 接口设计文档

| 版本 | 日期 | 范围 |
| :----: | :--------: | :------------------------: |
| v1.0.0 | 2026-11-03 | 用户、瞬间及必要依赖 |

## 接口规范

### 基础配置

```
Base URL: http://localhost:8080
API Version: v1
API Prefix: /
```

版本为契约版本，不加入 URL；部署环境使用 HTTPS，替换 Base URL，保留相对路径。

约定不同业务模块前缀：

- 用户认证：`auth`
- 用户资料：`users`
- 瞬间：`moments`
- 媒体：`media`
- 时光：`album`
- 同行：`gathering`
- 圈子：`circle`
- 消息：`message`
- AI：`ai`

### 接口风格

采用 RESTful 风格，使用名词表示资源，示例：

| 操作 | HTTP Method | URL 示例 |
| --- | --- | --- |
| 创建用户 | POST | `/auth/register` |
| 创建登录会话 | POST | `/auth/login` |
| 查询当前用户 | GET | `/users/me` |
| 部分更新瞬间 | PATCH | `/moments/{momentId}` |
| 删除瞬间 | DELETE | `/moments/{momentId}` |

### 数据格式

- JSON 使用 UTF-8、camelCase，`Content-Type: application/json`。
- 业务接口使用 JSON 申请上传地址，图片通过预签名 URL 向对象存储直传原始字节。
- JSON 写入请求体最大 **1 MiB**；图片大小限制由上传接口定义。
- 参数位置明确标注 `Header`、`Path`、`Query`，其余默认为 `Body`。

### 响应与错误处理

- 成功响应：`{code: "SUCCESS", message, data}`；首次发布瞬间返回 **201**，用户注册及其他接口成功状态码见各接口。
- 错误响应：`{code, message, data: null}`；可附 `errors: [{field, reason, targetId}]`，无目标时 `targetId=null`。
- 错误响应不得暴露堆栈、数据库等内部信息。

### 时间规范

- 响应时间使用 **UTC**，瞬间时间使用毫秒精度，例如`2026-11-03T02:00:00.000Z`。
- 输入时间必须包含时区，服务端统一转换为 UTC。
- 时间区间采用 **左闭右开** `**[start, end)**`。
- 记忆时间未传时默认为当前时刻；客户端按用户时区展示及选择日期。

### 认证与授权

使用 JWT Bearer Token，示例：

```
GET /users/me
Authorization: Bearer eyJhbGciOi...
```

- 注册、登录公开；其他接口需要 JWT。
- 令牌固定有效期 7 天（604800 秒），无刷新接口，到期重新登录。
- 后端使用 HS256 签发令牌，鉴权时验证签名、签发者、有效期及令牌撤销状态。

## 用户模块

### 接口清单

| 接口名称 | 方法 | 路径 |
| --- | --- | --- |
| 用户注册 | POST | `/auth/register` |
| 用户登录 | POST | `/auth/login` |
| 退出登录 | POST | `/auth/logout` |
| 获取当前用户资料 | GET | `/users/me` |
| 更新当前用户资料 | PUT | `/users/me` |

### 用户注册

POST `/auth/register` 公开接口

创建用户名密码账号，注册成功后由客户端进入登录。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 用户注册 |
| 请求方式 | POST |
| 请求路径 | `/auth/register` |
| 认证要求 | 不需要 |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| username | string | 是 | 4～64 位 ASCII 字母、数字、下划线；首位字母；统一转小写后唯一 |
| password | string | 是 | 8～64 字符，至少包含字母与数字，不截断、不去空白 |
| nickname | string | 是 | 去首尾空白后 1～64 字符，允许重名 |

#### 请求示例

```json
{
  "username": "calmer",
  "password": "Example12345",
  "nickname": "小满"
}
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 用户 UUIDv7 |
| username | string | 本人登录账号，仅本人接口返回 |
| nickname | string | 昵称 |
| avatarFileId | string / null | 头像文件 ID |
| avatarUrl | string / null | 有效期为 10 分钟的预签名 GET 地址，头像未设置或文件记录不可用时为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料最后更新时间，UTC |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000001",
    "username": "calmer",
    "nickname": "小满",
    "avatarFileId": null,
    "avatarUrl": null,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 对 username 进行统一规范化，唯一索引冲突映射为 USERNAME_ALREADY_EXISTS；软删除账号仍占用原 username，不自动复用。
- 仅存自适应加盐密码散列，不返回散列、明文密码，不将密码写入日志；注册无邮箱或手机验证。
- 注册不自动签发令牌。结果未知先尝试登录同一账号；用户名被占用不等于原注册成功。重复注册不覆盖密码或资料。
- 初始头像为空，昵称不作为登录标识。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | USERNAME_ALREADY_EXISTS | 用户名已被使用 |
| 400 | INVALID_ARGUMENT | 账号、密码或昵称不符合要求 |
| 400 | INVALID_REQUEST_BODY | 请求体格式或字段类型不正确 |

### 用户登录

POST `/auth/login` 公开接口

校验用户名与密码并签发有效期为 7 天的访问令牌。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 用户登录 |
| 请求方式 | POST |
| 请求路径 | `/auth/login` |
| 认证要求 | 不需要 |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| username | string | 是 | 与注册相同的格式要求，登录时忽略大小写 |
| password | string | 是 | 8～64 字符，保留原始内容及首尾空白 |

#### 请求示例

```json
{
  "username": "calmer",
  "password": "Example12345"
}
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| accessToken | string | JWT 访问令牌 |
| tokenType | string | 固定 Bearer |
| expiresAt | string | 令牌过期时间，UTC |
| user | object | 本人资料，结构同获取当前用户资料 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "expiresAt": "2026-11-10T02:00:00Z",
    "user": {
      "id": "019a48d0-0000-7000-8000-000000000001",
      "username": "calmer",
      "nickname": "小满",
      "avatarFileId": null,
      "avatarUrl": null,
      "createdAt": "2026-11-03T02:00:00.000Z",
      "updatedAt": "2026-11-03T02:00:00.000Z"
    }
  }
}
```

#### 业务规则

- 不存在、密码错误、账号已删除统一返回 INVALID_CREDENTIALS，不签发令牌。
- 每次成功登录签发一个新的访问令牌。
- 客户端安全保存令牌，账号切换取消旧请求并隔离缓存。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | 账号或密码不正确 |
| 400 | INVALID_ARGUMENT | 用户名格式或密码长度不符合要求 |
| 400 | INVALID_REQUEST_BODY | 请求体格式或字段类型不正确 |

### 退出登录

POST `/auth/logout` 受保护接口

撤销请求中携带的访问令牌，结束当前登录。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 退出登录 |
| 请求方式 | POST |
| 请求路径 | `/auth/logout` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

无业务请求参数。受保护接口携带 Authorization 请求头。

#### 请求示例

```http
POST /auth/logout
Authorization: Bearer <accessToken>
```

#### 响应数据

成功响应的 data 为 null。

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": null
}
```

#### 业务规则

- 服务端按当前令牌的 jti 写入 Redis 撤销记录，记录保留至该令牌过期。其他令牌继续有效。
- 客户端先处理未保存内容，再清除令牌及受保护页面和缓存；按账号保存的本地草稿不得向其他账号展示。
- 使用已撤销的令牌再次调用受保护接口时返回 401。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 令牌缺失、无效、已过期或已撤销 |

### 获取当前用户资料

GET `/users/me` 受保护接口

获取本人的账号及个人资料。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 获取当前用户资料 |
| 请求方式 | GET |
| 请求路径 | `/users/me` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

无业务请求参数。受保护接口携带 Authorization 请求头。

#### 请求示例

```http
GET /users/me
Authorization: Bearer <accessToken>
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 用户 UUIDv7 |
| username | string | 本人登录账号，仅本人接口返回 |
| nickname | string | 昵称 |
| avatarFileId | string / null | 头像文件 ID |
| avatarUrl | string / null | 有效期为 10 分钟的预签名 GET 地址，头像未设置或文件记录不可用时为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料最后更新时间，UTC |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000001",
    "username": "calmer",
    "nickname": "小满",
    "avatarFileId": "019a48d0-0000-7000-8000-000000000003",
    "avatarUrl": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 只读取 JWT sub 对应用户，不接受 userId 参数。
- 已绑定有效头像时，客户端使用 avatarUrl 直接向对象存储发起 GET。签名地址过期后重新获取当前用户资料，长期引用使用 avatarFileId。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 账号不存在、已删除，或令牌缺失、无效、已过期或已撤销 |

### 更新当前用户资料

PUT `/users/me` 受保护接口

全量更新本人昵称和头像，返回最新个人资料。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 更新当前用户资料 |
| 请求方式 | PUT |
| 请求路径 | `/users/me` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| nickname | string | 是 | 去除首尾空白后 1～64 字符 |
| avatarFileId | string / null | 否 | 已登记的本人图片 UUID；不传或传 null 均移除头像 |

#### 请求示例

```json
{
  "nickname": "小满",
  "avatarFileId": "019a48d0-0000-7000-8000-000000000003"
}
```

请求须携带 Authorization 请求头及 Content-Type: application/json。

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 用户 UUIDv7 |
| username | string | 本人登录账号，仅本人接口返回 |
| nickname | string | 昵称 |
| avatarFileId | string / null | 头像文件 ID |
| avatarUrl | string / null | 有效期为 10 分钟的预签名 GET 地址，头像未设置或文件记录不可用时为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料最后更新时间，UTC |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000001",
    "username": "calmer",
    "nickname": "小满",
    "avatarFileId": "019a48d0-0000-7000-8000-000000000003",
    "avatarUrl": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.001Z"
  }
}
```

#### 业务规则

- 每次提交昵称与目标头像。仅修改昵称时带上当前 avatarFileId，以保留现有头像。
- 更换头像时，通过媒体模块申请上传并直传图片，等上传状态 ready 为 true 后，将 fileId 作为 avatarFileId 提交。
- 服务端校验文件已登记、有效且属于当前用户。移除头像仅清除用户资料中的引用。
- 更新成功后返回最新资料，已绑定头像的 avatarUrl 为预签名 GET 地址。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT | 昵称缺失或不符合要求 |
| 400 | INVALID_REQUEST_BODY | 请求体格式、字段类型或头像 UUID 不正确 |
| 400 | FILE_NOT_USABLE | 头像文件不存在、非本人或已删除 |
| 401 | UNAUTHORIZED | 账号不可用或令牌缺失、无效、已过期或已撤销 |

## 瞬间模块

### 接口清单

本节为待实现的接口契约。标注暂不实现的条目仅保留在清单中。

| 接口名称 | 方法 | 路径 |
| --- | --- | --- |
| 发布瞬间 | POST | `/moments/` |
| 浏览瞬间信息流 | GET | `/moments` |
| 获取瞬间详情 | GET | `/moments/{momentId}` |
| 编辑瞬间 | PATCH | `/moments/{momentId}` |
| 删除瞬间 | DELETE | `/moments/{momentId}` |
| 获取瞬间可见范围（暂不实现） | GET | `/moments/{momentId}/visibility` |
| 替换瞬间关联与分享（暂不实现） | PUT | `/moments/{momentId}/visibility` |
| 查询可选关联与分享目标（暂不实现） | GET | `/visibility-targets` |

### 发布瞬间

POST `/moments/` 受保护接口

一次创建原瞬间、图片、标签及确认的同行与圈子关系。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 发布瞬间 |
| 请求方式 | POST |
| 请求路径 | `/moments/` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 201；同 ID 已存在返回 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | string | 是 | 客户端预生成 UUIDv7；本次发布全过程及重试保持一致 |
| title | string / null | 否 | 标题，最多 255 字符 |
| body | string / null | 否 | 普通文本，UTF-8 编码最多 60000 字节；与图片至少有一种有效内容 |
| imageFileIds | array<string> | 否 | 图片文件 ID，默认 []；最多 20 张，不重复，数组顺序为展示顺序 |
| tags | array<string> | 否 | 默认 []；最多 20 个，每个去除首尾空白后 1～64 字符，同一瞬间内不允许重复；首尾空白规范化后重复返回 400 |
| mood | string / null | 否 | 心情枚举，仅允许 开心、平静、松弛、心动、惊喜、自在、低落；null 表示未设置 |
| memoryTime | string | 否 | 发生时间，带时区的 RFC 3339 时间；不传采用服务端当前时间 |
| location | object / null | 否 | 地点；默认 null |
| location.name | string / null | 否 | 地点名称，最多 255 字符 |
| location.address | string / null | 否 | 地点地址，最多 512 字符 |
| location.latitude | number / null | 否 | 纬度 -90～90，最多 7 位小数，与 longitude 同时为空或同时有值 |
| location.longitude | number / null | 否 | 经度 -180～180，最多 7 位小数 |
| journeyId | string / null | 否 | 最多一个有效同行，默认 null |
| circleIds | array<string> | 否 | 目标圈子 ID，默认 []，最多 100 个且不可重复 |

#### 请求示例

```http
POST /moments/
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "id": "019a48d0-0000-7000-8000-000000000002",
  "title": "海边的一天",
  "body": "今天一起看海。",
  "imageFileIds": ["019a48d0-0000-7000-8000-000000000003"],
  "tags": [
    "旅行"
  ],
  "mood": "开心",
  "memoryTime": "2026-11-03T02:00:00.000Z",
  "location": null,
  "journeyId": "019a48d0-0000-7000-8000-000000000004",
  "circleIds": []
}
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 UUIDv7 |
| author | object | 公开作者资料，包含 id、nickname、avatarFileId、avatarUrl；有可用头像时 avatarUrl 为临时读取地址 |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].url | string | 有效期为 10 分钟的预签名 GET 地址 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；以 updatedAt 为版本凭证 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "author": {
      "id": "019a48d0-0000-7000-8000-000000000001",
      "nickname": "小满",
      "avatarFileId": null,
      "avatarUrl": null
    },
    "title": "海边的一天",
    "body": "今天一起看海。",
    "images": [
      {
        "fileId": "019a48d0-0000-7000-8000-000000000003",
        "sortOrder": 1,
        "url": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
        "mimeType": "image/jpeg"
      }
    ],
    "tags": [
      "旅行"
    ],
    "mood": "开心",
    "memoryTime": "2026-11-03T02:00:00.000Z",
    "location": null,
    "sources": [
      {
        "type": "journey",
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
      }
    ],
    "collections": [],
    "isOwn": true,
    "canEdit": true,
    "canDelete": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 客户端在首次请求前将 ID 和完整输入写入本地未决请求；作者由 JWT 确定。正文非空白或至少一张有效图片，否则拒绝。
- 图片先通过媒体模块直传，待 ready 为 true 后提交 fileId。文件均须属于当前用户且有效。按 imageFileIds 顺序创建 sort_order=1..N；不得静默跳过失败图片。
- 校验同行与全部圈子及当前成员资格，锁定相关资格记录，权限变更操作使用相同锁定协议；所有内容、关系与应生成的 user_inbox 动态在同一 MySQL 事务生效，任一目标失效全部回滚。
- 请求 ID 已存在且属于当前用户、未删除时返回 200 与当前资源，createdAt 不变，不重新写入请求内容、不追加分享或重复动态。已删除的本人 ID 返回 409 MOMENT_DELETED；他人占用 ID 返回 409 RESOURCE_CONFLICT，不返回其资料。首次创建返回 201。
- 此策略仅保证同一 ID 最多创建一条记录，不验证历史请求正文一致性、不重放最初响应。客户端收到已有资源须核对内容；修改另走编辑接口，禁止换 ID 盲目重试。
- 结果未知先 GET /moments/{id}；200 表示记录已创建；404 时可用原 ID 重试 POST，由发布接口区分首次创建、已删除或 ID 冲突。
- 未分享时仅作者可见；无参与人员、评论或 AI 字段。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | EMPTY_CONTENT | 正文及有效图片均为空 |
| 400 | FILE_NOT_USABLE | 图片不属于本人或不可用 |
| 409 | TARGET_UNAVAILABLE | 同行或圈子已失效、无成员资格；errors 标明请求中的问题目标 ID |
| 409 | MOMENT_DELETED | 该发布 ID 已用于本人已删除瞬间 |
| 409 | RESOURCE_CONFLICT | 该 UUID 已被其他记录占用 |

### 浏览瞬间信息流

GET `/moments` 受保护接口

按 Yours 或 Related 分页浏览瞬间，供客户端渲染瀑布流。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 浏览瞬间信息流 |
| 请求方式 | GET |
| 请求路径 | `/moments` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| tab | string | 是 | Query；yours 或 related |
| cursor | string | 否 | Query；上一页的 nextCursor，首屏或刷新时不传 |
| limit | integer | 否 | Query；默认 20，1～100 |

#### 请求示例

```http
GET /moments?tab=related&limit=20
Authorization: Bearer <accessToken>
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | array<object> | 元素为瞬间对象，字段同发布响应；collections 仅详情返回，列表省略；body 返回完整普通文本 |
| nextCursor | string / null | 下一页游标，到底时为 null |
| hasMore | boolean | 是否还有当前有权访问的瞬间 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "items": [
      {
        "id": "019a48d0-0000-7000-8000-000000000002",
        "author": {
          "id": "019a48d0-0000-7000-8000-000000000001",
          "nickname": "小满",
          "avatarFileId": null,
          "avatarUrl": null
        },
        "title": "海边的一天",
        "body": "今天一起看海。",
        "images": [
          {
            "fileId": "019a48d0-0000-7000-8000-000000000003",
            "sortOrder": 1,
            "url": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
            "mimeType": "image/jpeg"
          }
        ],
        "tags": [
          "旅行"
        ],
        "mood": "开心",
        "memoryTime": "2026-11-03T02:00:00.000Z",
        "location": null,
        "sources": [
          {
            "type": "journey",
            "id": "019a48d0-0000-7000-8000-000000000004",
            "name": "毕业旅行",
            "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
          }
        ],
        "isOwn": true,
        "canEdit": true,
        "canDelete": true,
        "createdAt": "2026-11-03T02:00:00.000Z",
        "updatedAt": "2026-11-03T02:00:00.000Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

#### 业务规则

- yours 仅本人未删除瞬间；related 为有效同行和圈子直接分享的并集，按 ID 去重，保留当前全部合法来源，本人已分享记录可同时出现在两个 Tab。
- 固定按 memoryTime 降序，同一时间按 id 降序。游标对客户端不透明，与当前用户及 Tab 绑定，记录上一页末项的 memoryTime 和 id。续页从该排序位置之后读取。
- 每页按当前权限查询，按瞬间 ID 去重后读取 limit + 1 条判断 hasMore。有下一页时以本页末项生成 nextCursor。无数据返回 items=[]、nextCursor=null、hasMore=false。
- 各 Tab 独立维护列表和游标。客户端接近底部时加载下一页，按顺序追加并按 ID 去重。刷新时从首屏重新加载，刷新或得知权限变化时移除失效条目。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | tab、limit 或游标无效 |

### 获取瞬间详情

GET `/moments/{momentId}` 受保护接口

返回最新原内容及当前可访问关联，同时用于确认发布是否生效。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 获取瞬间详情 |
| 请求方式 | GET |
| 请求路径 | `/moments/{momentId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |

#### 请求示例

```http
GET /moments/019a48d0-0000-7000-8000-000000000002
Authorization: Bearer <accessToken>
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 UUIDv7 |
| author | object | 公开作者资料，包含 id、nickname、avatarFileId、avatarUrl；有可用头像时 avatarUrl 为临时读取地址 |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].url | string | 有效期为 10 分钟的预签名 GET 地址 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；以 updatedAt 为版本凭证 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "author": {
      "id": "019a48d0-0000-7000-8000-000000000001",
      "nickname": "小满",
      "avatarFileId": null,
      "avatarUrl": null
    },
    "title": "海边的一天",
    "body": "今天一起看海。",
    "images": [
      {
        "fileId": "019a48d0-0000-7000-8000-000000000003",
        "sortOrder": 1,
        "url": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
        "mimeType": "image/jpeg"
      }
    ],
    "tags": [
      "旅行"
    ],
    "mood": "开心",
    "memoryTime": "2026-11-03T02:00:00.000Z",
    "location": null,
    "sources": [
      {
        "type": "journey",
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
      }
    ],
    "collections": [],
    "isOwn": true,
    "canEdit": true,
    "canDelete": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 每次检查原记录、当前成员与分享关系，不信任列表或通知曾经可见。
- 作者取得全部有效来源；非作者只取得自身有权进入的来源；时光引用按当前时光资格过滤，不能从时光分享反向获得原瞬间读取资格。
- 返回 ETag，其值为双引号包裹的 updatedAt；权限失效或原记录已删除不返回旧正文、图片及来源名称。
- 图片 URL 仅在通过瞬间读取权限校验后签发。客户端直接向对象存储发起 GET，过期后重新获取详情。已签发地址在有效期内仍可使用。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 404 | RESOURCE_NOT_FOUND | 记录不存在、已删除或无权访问 |

### 编辑瞬间

PATCH `/moments/{momentId}` 受保护接口

部分更新作者本人的原内容，可一并调整同行和圈子范围。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 编辑瞬间 |
| 请求方式 | PATCH |
| 请求路径 | `/moments/{momentId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |
| If-Match | string | 是 | Header；双引号包裹最近 updatedAt |
| title | string / null | 否 | 标题，最多 255 字符 |
| body | string / null | 否 | 普通文本，UTF-8 编码最多 60000 字节；与图片至少有一种有效内容 |
| imageFileIds | array<string> | 否 | 图片文件 ID，新建默认 []；编辑时传 [] 清空；最多 20 张，不重复，数组顺序为展示顺序 |
| tags | array<string> | 否 | 新建默认 []；编辑时传 [] 清空；最多 20 个，每个去除首尾空白后 1～64 字符，同一瞬间内不允许重复；首尾空白规范化后重复返回 400 |
| mood | string / null | 否 | 心情枚举，仅允许 开心、平静、松弛、心动、惊喜、自在、低落；null 表示未设置 |
| memoryTime | string | 否 | 发生时间，带时区的 RFC 3339 时间；编辑不传保持原值，不允许 null |
| location | object / null | 否 | 地点；默认 null |
| location.name | string / null | 否 | 地点名称，最多 255 字符 |
| location.address | string / null | 否 | 地点地址，最多 512 字符 |
| location.latitude | number / null | 否 | 纬度 -90～90，最多 7 位小数，与 longitude 同时为空或同时有值 |
| location.longitude | number / null | 否 | 经度 -180～180，最多 7 位小数 |
| journeyId | string / null | 否 | 不传保持，null 取消，传 ID 替换同行 |
| circleIds | array<string> | 否 | 不传保持，传数组整体替换，[] 撤销全部，最多 100 个 |

#### 请求示例

```http
PATCH /moments/019a48d0-0000-7000-8000-000000000002
Authorization: Bearer <accessToken>
Content-Type: application/json
If-Match: "2026-11-03T02:00:00.000Z"

{
  "body": "傍晚又一起看了日落。",
  "tags": [
    "旅行",
    "日落"
  ]
}
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 UUIDv7 |
| author | object | 公开作者资料，包含 id、nickname、avatarFileId、avatarUrl；有可用头像时 avatarUrl 为临时读取地址 |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].url | string | 有效期为 10 分钟的预签名 GET 地址 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；以 updatedAt 为版本凭证 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "author": {
      "id": "019a48d0-0000-7000-8000-000000000001",
      "nickname": "小满",
      "avatarFileId": null,
      "avatarUrl": null
    },
    "title": "海边的一天",
    "body": "傍晚又一起看了日落。",
    "images": [
      {
        "fileId": "019a48d0-0000-7000-8000-000000000003",
        "sortOrder": 1,
        "url": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example",
        "mimeType": "image/jpeg"
      }
    ],
    "tags": [
      "旅行",
      "日落"
    ],
    "mood": "开心",
    "memoryTime": "2026-11-03T02:00:00.000Z",
    "location": null,
    "sources": [
      {
        "type": "journey",
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
      }
    ],
    "collections": [],
    "isOwn": true,
    "canEdit": true,
    "canDelete": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.001Z"
  }
}
```

#### 业务规则

- 更新成功后返回更新后的瞬间及 ETag，其值为双引号包裹的 updatedAt。
- 至少传一个可编辑字段；合并后再次校验正文或图片至少有一种，memoryTime 不可 null。
- 锁 moment，校验版本、文件及新增或保留目标资格；原内容与全部关系在同一事务更新，任一失败全部回滚。
- images、tags 和 circleIds 都是完整目标集合，不作隐式追加；原发布时间、ID、作者不变。
- 修改内容不改变圈子原分享时间，不重复产生内容动态；新建或撤销后恢复分享依关系规则生成对应动态，已有引用继续读最新内容。
- 客户端确认可见范围后才提交；超时读取详情核对当前内容，旧版本冲突保留本地输入，由用户决定合并。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | VERSION_CONFLICT | 旧版本不能覆盖当前记录 |
| 400 | EMPTY_CONTENT | 合并后正文和图片均为空 |
| 400 | FILE_NOT_USABLE | 图片无效或非本人 |
| 409 | TARGET_UNAVAILABLE | 任一关系目标失效，整次不生效 |
| 403 | FORBIDDEN | 有读取资格但非作者 |
| 404 | RESOURCE_NOT_FOUND | 记录不存在、已删除或无权访问 |
| 428 | PRECONDITION_REQUIRED | 未提供 If-Match |

### 删除瞬间

DELETE `/moments/{momentId}` 受保护接口

作者确认删除原瞬间，使所有入口立即停止展示。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 删除瞬间 |
| 请求方式 | DELETE |
| 请求路径 | `/moments/{momentId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |
| If-Match | string | 是 | Header；双引号包裹最近 updatedAt |

#### 请求示例

```http
DELETE /moments/019a48d0-0000-7000-8000-000000000002
Authorization: Bearer <accessToken>
If-Match: "2026-11-03T02:00:00.000Z"
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 ID |
| deleted | boolean | 固定 true |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "deleted": true
  }
}
```

#### 业务规则

- 作者须在客户端确认删除影响，再提交；服务端校验作者和版本。
- 事务中软删除 moment，清空 journey_id，软删除有效图片、标签、collection_moment 和对应 moment 类型 circle_share 关系；图片文件只移除引用，不直接物理删除。
- 所有列表、数量、信箱、通知目标和读取接口实时检查原记录；清理搜索索引等派生内容前也不可继续返回旧正文。时光、同行和圈子本身保留。
- 本人重复删除同一已删除 ID 返回 200 deleted=true，不再比较旧版本；不存在或他人已删除记录返回 404。结果未知使用原 ID 重试删除确认；不提供恢复接口。
- 无客户端幂等历史，删除结果仅证明当前记录已被删除。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | VERSION_CONFLICT | 未删除记录已被其他操作修改 |
| 403 | FORBIDDEN | 可读取但非作者 |
| 404 | RESOURCE_NOT_FOUND | 不存在或无资格确认删除 |
| 428 | PRECONDITION_REQUIRED | 未提供 If-Match |

## 媒体模块

### 接口清单

| 接口名称 | 方法 | 路径 |
| --- | --- | --- |
| 申请图片上传地址 | POST | `/media/uploads` |
| 查询图片上传就绪状态 | GET | `/media/uploads/{fileId}` |

### 申请图片上传地址

POST `/media/uploads` 受保护接口

根据当前用户及声明的图片信息生成文件 ID 和有效期为 10 分钟的预签名 PUT 地址。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 申请图片上传地址 |
| 请求方式 | POST |
| 请求路径 | `/media/uploads` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| mimeType | string | 是 | 图片 MIME 类型，支持 image/jpeg、image/png、image/webp |
| byteSize | integer | 是 | 声明的文件字节数，范围为 1～10485760 字节（10 MiB） |

#### 请求示例

```http
POST /media/uploads
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "mimeType": "image/jpeg",
  "byteSize": 204800
}
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| fileId | string | 本次申请生成的文件 UUID，用于查询上传就绪状态 |
| url | string | 对象存储的完整预签名 PUT 地址，有效期为 10 分钟 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "fileId": "019a48d0-0000-7000-8000-000000000003",
    "url": "https://storage.example.com/you-files/images/019a48d0-0000-7000-8000-000000000001/019a48d0-0000-7000-8000-000000000003?X-Amz-Expires=600&X-Amz-Signature=example"
  }
}
```

#### 业务规则

- 文件归属由 JWT 确定，每次申请生成新的文件 ID。申请成功时尚未建立 media_file 记录。
- 客户端使用返回的完整 URL 向对象存储发起 PUT 请求，请求体为图片原始字节，Content-Type 与申请时的 mimeType 一致。直传请求无需携带业务 JWT。
- 直传成功后，服务端异步处理对象存储事件，检查对象实际字节数及 Content-Type，符合要求后登记 media_file 记录。
- 客户端通过查询图片上传就绪状态确认文件已登记，待 ready 为 true 后再提交业务引用。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT | 必填字段缺失，或 byteSize 超出允许范围 |
| 400 | INVALID_REQUEST_BODY | 请求体格式、字段类型或 mimeType 枚举值不正确 |
| 401 | UNAUTHORIZED | 登录凭证缺失、无效或已过期 |

### 查询图片上传就绪状态

GET `/media/uploads/{fileId}` 受保护接口

根据文件 ID 查询当前用户的图片是否已登记并可用于业务关联。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 查询图片上传就绪状态 |
| 请求方式 | GET |
| 请求路径 | `/media/uploads/{fileId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| fileId | string | 是 | Path；申请图片上传地址时返回的文件 UUID |

#### 请求示例

```http
GET /media/uploads/019a48d0-0000-7000-8000-000000000003
Authorization: Bearer <accessToken>
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| fileId | string | 本次查询的文件 UUID |
| ready | boolean | 存在属于当前用户的有效文件记录时为 true，其余情况为 false |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "fileId": "019a48d0-0000-7000-8000-000000000003",
    "ready": true
  }
}
```

#### 业务规则

- 仅查询当前用户拥有且未删除的文件记录。
- 文件未登记、已删除或属于其他用户时，均返回 200，ready 为 false。
- 对象存储返回上传成功后，文件登记可能仍在进行，客户端可再次查询。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | — | fileId 不是合法 UUID，由框架处理路径参数转换错误 |
| 401 | UNAUTHORIZED | 登录凭证缺失、无效或已过期 |


## 时光信息维护

以下接口使用 Bearer JWT 认证，路径中的 `albumId` 为时光 UUID。

### 修改时光信息

`PUT /albums/{albumId}` 全量提交三个展示字段，瞬间增减由独立接口处理。

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| name | string | 是 | 去首尾空白后 1～128 个 Unicode 码点，允许重名 |
| description | string 或 null | 是 | 最多 5000 个 Unicode 码点，null 清空 |
| coverFileId | UUID 或 null | 是 | 本人已上传就绪且未删除的图片，null 清空 |

成功返回 HTTP 200，统一响应的 data 使用 AlbumResponse，包含 id、name、description、coverFileId、coverUrl、organizeMode、momentCount、createdAt、updatedAt。修改后更新 UTC 修改时间，保留创建时间及整理设置；momentCount 统计有效关联中的本人有效瞬间，封面 URL 按现有媒体规则签名。

### 删除时光

`DELETE /albums/{albumId}` 校验归属后，在同一事务内软删除时光及其尚有效的瞬间关联，保留原瞬间、媒体文件和其他时光的关联。本人重复删除已删除时光返回成功，保留原删除时间。成功返回 HTTP 200，响应为 `{"code":"SUCCESS","message":"操作成功","data":null}`。删除后详情及瞬间分页入口返回 404。

### 错误响应

| HTTP 状态码 | 场景 |
| --- | --- |
| 400 | UUID 格式错误、修改字段缺失、名称或介绍不合法、指定封面不可用 |
| 401 | 登录凭证缺失、无效或已失效 |
| 403 | 当前用户不是时光所有者，重复删除也须校验归属 |
| 404 | 时光不存在；修改或访问已删除时光；本人重复删除除外 |
