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

- 用户：`user`
- 瞬间：`moment`
- 时光：`album`
- 同行：`gathering`
- 圈子：`circle`
- 消息：`message`
- AI：`ai`

### 接口风格

采用 RESTful 风格，使用名词表示资源，示例：

| 操作 | HTTP Method | URL 示例 |
| --- | --- | --- |
| 创建用户 | POST | `/user/users` |
| 创建登录会话 | POST | `/user/sessions` |
| 查询当前用户 | GET | `/user/users/me` |
| 部分更新瞬间 | PATCH | `/moment/moments/{momentId}` |
| 替换可见范围 | PUT | `/moment/moments/{momentId}/visibility` |
| 删除瞬间 | DELETE | `/moment/moments/{momentId}` |

### 数据格式

- JSON 使用 UTF-8、camelCase，`Content-Type: application/json`。
- 文件上传使用 `multipart/form-data`，媒体内容以二进制返回。
- JSON 写入请求体最大 **1 MiB**；图片大小限制由上传接口定义。
- 参数位置明确标注 `Header`、`Path`、`Query`，其余默认为 `Body`。

### 响应与错误处理

- 成功响应：`{code: "SUCCESS", message, data}`；创建用户、瞬间、文件返回 **201**，其他成功返回 **200**。
- 错误响应：`{code, message, data: null}`；可附 `errors: [{field, reason, targetId}]`，无目标时 `targetId=null`。
- 错误响应不得暴露堆栈、数据库等内部信息。

### 时间规范

- 响应时间统一为 **UTC 毫秒精度**，格式：`2026-11-03T02:00:00.000Z`。
- 输入时间必须包含时区，服务端统一转换为 UTC。
- 时间区间采用 **左闭右开** `**[start, end)**`。
- 记忆时间未传时默认为当前时刻；客户端按用户时区展示及选择日期。

### 认证与授权

使用 JWT Bearer Token，示例：

```
GET /user/users/me
Authorization: Bearer eyJhbGciOi...
```

- 注册、登录公开；其他接口需要 JWT。
- 令牌固定有效期 7 天（604800 秒），无刷新接口，到期重新登录。
- 验证签名、有效期、签发者和受众；服务端仅接受固定配置的签名算法。

## 用户模块

### 接口清单

| 接口名称 | 方法 | 路径 |
| --- | --- | --- |
| 用户注册 | POST | `/user/users` |
| 用户登录 | POST | `/user/sessions` |
| 退出登录 | DELETE | `/user/sessions/current` |
| 获取当前用户资料 | GET | `/user/users/me` |
| 更新当前用户资料 | PATCH | `/user/users/me` |
| 上传头像图片 | POST | `/user/avatar-files` |
| 读取头像图片 | GET | `/user/avatar-files/{fileId}/content` |

### 用户注册

POST `/user/users` 公开接口

创建用户名密码账号，注册成功后由客户端进入登录。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 用户注册 |
| 请求方式 | POST |
| 请求路径 | `/user/users` |
| 认证要求 | 不需要 |
| 成功状态码 | 201 |

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
| avatarContentPath | string / null | 受保护的头像读取路径，未设置为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料版本凭证 |

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
    "avatarContentPath": null,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 对 username 进行统一规范化，唯一索引冲突映射为 ACCOUNT_UNAVAILABLE；软删除账号仍占用原 username，不自动复用。
- 仅存自适应加盐密码散列，不返回散列、明文密码，不将密码写入日志；注册无邮箱或手机验证。
- 注册不自动签发令牌。结果未知先尝试登录同一账号；用户名被占用不等于原注册成功。重复注册不覆盖密码或资料。
- 初始头像为空，昵称不作为登录标识。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | ACCOUNT_UNAVAILABLE | 账号不可用，调整账号或尝试登录 |
| 400 | INVALID_PARAMETER | 账号、密码或昵称不符合要求 |

### 用户登录

POST `/user/sessions` 公开接口

校验用户名与密码并签发有效期为 7 天的访问令牌。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 用户登录 |
| 请求方式 | POST |
| 请求路径 | `/user/sessions` |
| 认证要求 | 不需要 |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| username | string | 是 | 与注册相同的规范化规则 |
| password | string | 是 | 原始密码，不进行首尾空白处理 |

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
| expiresIn | integer | 固定 604800，单位秒（7 天） |
| user | object | 本人资料，结构同获取当前用户资料 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "tokenType": "Bearer",
    "expiresIn": 604800,
    "user": {
      "id": "019a48d0-0000-7000-8000-000000000001",
      "username": "calmer",
      "nickname": "小满",
      "avatarFileId": null,
      "avatarContentPath": null,
      "createdAt": "2026-11-03T02:00:00.000Z",
      "updatedAt": "2026-11-03T02:00:00.000Z"
    }
  }
}
```

#### 业务规则

- 不存在、密码错误、账号已删除统一返回 INVALID_CREDENTIALS，不签发令牌。
- 账号与 IP 维度限流；失败不建立部分登录状态，重复成功登录允许产生不同令牌。
- 客户端安全保存令牌，账号切换取消旧请求并隔离缓存。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | 账号或密码不正确 |
| 429 | RATE_LIMITED | 登录尝试过于频繁 |

### 退出登录

DELETE `/user/sessions/current` 受保护接口

提供退出动作确认，客户端销毁本机当前凭证。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 退出登录 |
| 请求方式 | DELETE |
| 请求路径 | `/user/sessions/current` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

无业务请求参数。受保护接口携带 Authorization 请求头。

#### 请求示例

```http
DELETE /user/sessions/current
Authorization: Bearer <accessToken>
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| loggedOut | boolean | 固定 true，表示退出请求已处理，不表示 JWT 被撤销 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "loggedOut": true
  }
}
```

#### 业务规则

- 不写入数据库，不撤销任何已签发令牌；令牌到期前仍可访问。
- 客户端先处理未保存内容，再清除令牌及受保护页面和缓存；按账号保存的本地草稿不得向其他账号展示。
- 响应丢失、离线或 401 时仍执行本地退出；重复调用无额外副作用。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 令牌已过期，客户端仍完成退出 |

### 获取当前用户资料

GET `/user/users/me` 受保护接口

获取本人的账号及个人资料。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 获取当前用户资料 |
| 请求方式 | GET |
| 请求路径 | `/user/users/me` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

无业务请求参数。受保护接口携带 Authorization 请求头。

#### 请求示例

```http
GET /user/users/me
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 用户 UUIDv7 |
| username | string | 本人登录账号，仅本人接口返回 |
| nickname | string | 昵称 |
| avatarFileId | string / null | 头像文件 ID |
| avatarContentPath | string / null | 受保护的头像读取路径，未设置为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料版本凭证 |

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
    "avatarContentPath": null,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 只读取 JWT sub 对应用户，不接受 userId 参数。
- 返回 ETag，登录标识仅在本人资料接口中返回。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 用户不存在、已删除或令牌无效 |

### 更新当前用户资料

PATCH `/user/users/me` 受保护接口

部分更新本人昵称和头像。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 更新当前用户资料 |
| 请求方式 | PATCH |
| 请求路径 | `/user/users/me` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| If-Match | string | 是 | Header；双引号包裹最近 updatedAt |
| nickname | string | 否 | 1～64 字符，非空，不可为 null |
| avatarFileId | string / null | 否 | 已上传的本人头像文件 ID；null 移除头像 |

#### 请求示例

```json
{
  "nickname": "小满",
  "avatarFileId": null
}
```

请求头：`If-Match: "2026-11-03T02:00:00.000Z"`。

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 用户 UUIDv7 |
| username | string | 本人登录账号，仅本人接口返回 |
| nickname | string | 昵称 |
| avatarFileId | string / null | 头像文件 ID |
| avatarContentPath | string / null | 受保护的头像读取路径，未设置为 null |
| createdAt | string | 注册时间 |
| updatedAt | string | 资料版本凭证 |

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
    "avatarContentPath": null,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.001Z"
  }
}
```

#### 业务规则

- 至少提交一个可编辑字段；禁止修改 username、密码或他人资料。
- 先检查版本；头像须归当前用户、实际图片有效且未删除。变更在同一事务内生效。
- 只移除头像引用，不直接清理仍被其他有效用途引用的文件；保留作者身份及空间关系。
- 返回最新资料及 ETag；结果未知读取本人资料比较目标字段。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | VERSION_CONFLICT | 其他请求已修改资料 |
| 400 | FILE_NOT_USABLE | 头像文件不存在、非本人或已删除 |
| 428 | PRECONDITION_REQUIRED | 未提供版本凭证 |

### 上传头像图片

POST `/user/avatar-files` 受保护接口

接收图片，完成校验及对象存储写入后建立 media_file 记录。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 上传头像图片 |
| 请求方式 | POST |
| 请求路径 | `/user/avatar-files` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 201 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file | binary | 是 | multipart/form-data；仅一张 JPEG、PNG 或 WebP 图片；最大 10 MiB，最大 4000 万像素 |

#### 请求示例

```http
POST /user/avatar-files
Content-Type: multipart/form-data; boundary=<boundary>

--<boundary>
Content-Disposition: form-data; name="file"; filename="photo.jpg"
Content-Type: image/jpeg

<图片二进制>
--<boundary>--
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 新建 media_file ID |
| mimeType | string | 实际识别的 MIME 类型 |
| byteSize | integer | 实际字节数 |
| contentPath | string | 受保护读取路径 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000003",
    "mimeType": "image/jpeg",
    "byteSize": 204800,
    "contentPath": "/user/avatar-files/019a48d0-0000-7000-8000-000000000003/content"
  }
}
```

#### 业务规则

- 依据文件签名和实际解码校验，拒绝伪造类型及损坏图片；不采用客户端提供的文件名作为存储键。
- owner_user_id 由 JWT 决定；对象写入且检查成功后才建立文件记录。记录失败清理孤立对象，失败不返回文件 ID。
- 无上传任务或预签名上传接口；上传超时可重传，可能生成多个文件记录，不承诺上传幂等；客户端仅使用已确认返回的 ID。
- 引用提交失败不自动删除文件。仅无任何有效引用的文件可由服务端清理；引用建立与清理须串行校验文件有效性。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_IMAGE | 图片损坏、尺寸超限或无法解码 |
| 413 | PAYLOAD_TOO_LARGE | 图片超过 10 MiB |
| 415 | UNSUPPORTED_MEDIA_TYPE | 实际格式不支持 |
| 503 | STORAGE_UNAVAILABLE | 存储写入或校验失败 |

### 读取头像图片

GET `/user/avatar-files/{fileId}/content` 受保护接口

校验当前读取资格后代理输出图片二进制。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 读取头像图片 |
| 请求方式 | GET |
| 请求路径 | `/user/avatar-files/{fileId}/content` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| fileId | string | 是 | Path；media_file UUID |

#### 请求示例

```http
GET /user/avatar-files/019a48d0-0000-7000-8000-000000000003/content
Authorization: Bearer <accessToken>
```

#### 响应数据

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| 响应正文 | binary | 原图片字节，不使用 JSON 包装 |
| Content-Type | Header | 实际图片 MIME 类型 |
| Content-Length | Header | 实际字节数 |
| Cache-Control | Header | private, no-store |

#### 响应示例

```http
HTTP/1.1 200 OK
Content-Type: image/jpeg
Cache-Control: private, no-store

<图片二进制>
```

#### 业务规则

- 上传者可预览本人有效文件；其他用户仅当该文件为其当前可访问瞬间作者、有效共同空间成员等可展示用户的当前头像时允许读取。昵称头像公开展示不意味着任意登录用户可枚举头像。
- 文件存在不等于可访问；校验引用和权限后才输出正文。错误响应仍为 application/json。
- 不发放 MinIO 永久地址或可绕过权限的下载链接，不支持公开缓存。权限撤销不能回收已下载数据，客户端在失权后清除本地缓存。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 404 | RESOURCE_NOT_FOUND | 文件不存在、已删除或当前无读取资格 |
| 503 | STORAGE_UNAVAILABLE | 合法文件对象暂时无法读取 |

## 瞬间模块

### 接口清单

| 接口名称 | 方法 | 路径 |
| --- | --- | --- |
| 发布瞬间 | POST | `/moment/moments` |
| 浏览与筛选瞬间 | GET | `/moment/moments` |
| 关键词搜索瞬间 | GET | `/moment/moments/search` |
| 获取瞬间详情 | GET | `/moment/moments/{momentId}` |
| 编辑瞬间 | PATCH | `/moment/moments/{momentId}` |
| 删除瞬间 | DELETE | `/moment/moments/{momentId}` |
| 获取瞬间可见范围 | GET | `/moment/moments/{momentId}/visibility` |
| 替换瞬间关联与分享 | PUT | `/moment/moments/{momentId}/visibility` |
| 查询可选关联与分享目标 | GET | `/moment/visibility-targets` |
| 上传瞬间图片 | POST | `/moment/files` |
| 读取瞬间图片 | GET | `/moment/files/{fileId}/content` |

### 发布瞬间

POST `/moment/moments` 受保护接口

一次创建原瞬间、图片、标签及确认的同行与圈子关系。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 发布瞬间 |
| 请求方式 | POST |
| 请求路径 | `/moment/moments` |
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

```json
{
  "id": "019a48d0-0000-7000-8000-000000000002",
  "title": "海边的一天",
  "body": "今天一起看海。",
  "imageFileIds": [],
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
| author | object | 公开作者资料，仅 id、nickname、avatarFileId、avatarContentPath；不含 username |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].contentPath | string | GET /moment/files/{fileId}/content；每次读取校验权限 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete / canManageVisibility | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；内容与可见范围共用版本 |

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
      "avatarContentPath": null
    },
    "title": "海边的一天",
    "body": "今天一起看海。",
    "images": [],
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
    "canManageVisibility": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 客户端在首次请求前将 ID 和完整输入写入本地未决请求；作者由 JWT 确定。正文非空白或至少一张有效图片，否则拒绝。
- 文件均须是本人已上传、未删除的图片。按 imageFileIds 顺序创建 sort_order=1..N；不得静默跳过失败图片。
- 校验同行与全部圈子及当前成员资格，锁定相关资格记录，权限变更操作使用相同锁定协议；所有内容、关系与应生成的 user_inbox 动态在同一 MySQL 事务生效，任一目标失效全部回滚。
- 请求 ID 已存在且属于当前用户、未删除时返回 200 与当前资源，createdAt 不变，不重新写入请求内容、不追加分享或重复动态。已删除的本人 ID 返回 409 MOMENT_DELETED；他人占用 ID 返回 409 RESOURCE_CONFLICT，不返回其资料。首次创建返回 201。
- 此策略仅保证同一 ID 最多创建一条记录，不验证历史请求正文一致性、不重放最初响应。客户端收到已有资源须核对内容；修改另走编辑接口，禁止换 ID 盲目重试。
- 结果未知先 GET /moment/moments/{id}；200 表示已创建，409 MOMENT_DELETED 表示本人原记录已删除（由重试 POST 确认），404 时可用原 ID 重试。并发原请求仍由唯一主键收敛。
- 未分享时仅作者可见；无参与人员、评论或 AI 字段。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | EMPTY_CONTENT | 正文及有效图片均为空 |
| 400 | FILE_NOT_USABLE | 图片不属于本人或不可用 |
| 409 | TARGET_UNAVAILABLE | 同行或圈子已失效、无成员资格；errors 标明请求中的问题目标 ID |
| 409 | MOMENT_DELETED | 该发布 ID 已用于本人已删除瞬间 |
| 409 | RESOURCE_CONFLICT | 该 UUID 已被其他记录占用 |

### 浏览与筛选瞬间

GET `/moment/moments` 受保护接口

按 Yours 或 Related 浏览，并叠加结构化筛选与排序。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 浏览与筛选瞬间 |
| 请求方式 | GET |
| 请求路径 | `/moment/moments` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| tab | string | 是 | Query；yours 或 related |
| memoryTimeFrom / memoryTimeTo | string | 否 | Query；带时区时间，左闭右开，from 必须早于 to |
| tags | array<string> | 否 | Query；重复键 tags=旅行&tags=海边，最多 20 个，全部匹配 |
| mood | string | 否 | Query；心情枚举精确匹配，仅允许 开心、平静、松弛、心动、惊喜、自在、低落；不传表示不按心情筛选 |
| locationKeyword | string | 否 | Query；地点名称或地址包含匹配，最多 255 字符 |
| authorId | string | 否 | Query；作者 ID，仅缩小授权范围 |
| sourceType | string | 否 | Query；journey 或 circle，与 sourceId 成对出现 |
| sourceId | string | 否 | Query；有效来源 ID，不扩张权限 |
| sort | string | 否 | Query；memoryTimeDesc 默认，或 memoryTimeAsc |
| cursor | string | 否 | Query；上一页 nextCursor，首屏不传 |
| limit | integer | 否 | Query；默认 20，1～100 |

#### 请求示例

```http
GET /moment/moments?tab=related&tags=%E6%97%85%E8%A1%8C&sort=memoryTimeDesc&limit=20
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | array<object> | 元素为瞬间对象，字段同发布响应；collections 仅详情返回，列表省略；body 返回完整普通文本 |
| nextCursor | string / null | 下一页游标 |
| hasMore | boolean | 是否还有当前有权访问的匹配结果 |

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
          "avatarContentPath": null
        },
        "title": "海边的一天",
        "body": "今天一起看海。",
        "images": [],
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
        "canManageVisibility": true,
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
- 各类别条件之间为 AND；同一瞬间多来源仅返回一次。sources 不只返回命中筛选的来源，而是当前用户可访问的全部有效来源。
- authorId、sourceId 不可用于探测无权内容，目标不存在或无资格时返回空列表；无数据返回 items=[]。
- 应用通用排序分页；失权后的下一次请求不再返回正文、图片或来源；客户端保留 Tab 和条件，移除已失效条目。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | tab、时间、条件组合或游标无效 |

### 关键词搜索瞬间

GET `/moment/moments/search` 受保护接口

在本人和相关可见瞬间并集中搜索，不依赖当前 Tab。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 关键词搜索瞬间 |
| 请求方式 | GET |
| 请求路径 | `/moment/moments/search` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| keyword | string | 否 | Query；去首尾空白后 1～200 字符，与至少一个筛选条件二选一 |
| memoryTimeFrom / memoryTimeTo | string | 否 | Query；带时区时间，左闭右开，from 必须早于 to |
| tags | array<string> | 否 | Query；重复键 tags=旅行&tags=海边，最多 20 个，全部匹配 |
| mood | string | 否 | Query；心情枚举精确匹配，仅允许 开心、平静、松弛、心动、惊喜、自在、低落；不传表示不按心情筛选 |
| locationKeyword | string | 否 | Query；地点名称或地址包含匹配，最多 255 字符 |
| authorId | string | 否 | Query；作者 ID，仅缩小授权范围 |
| sourceType | string | 否 | Query；journey 或 circle，与 sourceId 成对出现 |
| sourceId | string | 否 | Query；有效来源 ID，不扩张权限 |
| sort | string | 否 | Query；memoryTimeDesc 默认，或 memoryTimeAsc |
| cursor | string | 否 | Query；上一页 nextCursor，首屏不传 |
| limit | integer | 否 | Query；默认 20，1～100 |

#### 请求示例

```http
GET /moment/moments/search?keyword=%E7%9C%8B%E6%B5%B7&limit=20
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | array<object> | 元素为瞬间对象，字段同发布响应；collections 仅详情返回，列表省略；body 返回完整普通文本 |
| nextCursor | string / null | 下一页游标 |
| hasMore | boolean | 是否还有当前有权访问的匹配结果 |

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
          "avatarContentPath": null
        },
        "title": "海边的一天",
        "body": "今天一起看海。",
        "images": [],
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
        "canManageVisibility": true,
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

- 不接收 tab；搜索范围固定为 Yours 与 Related 并集。关键词或至少一个业务筛选条件须存在，单独 sort 或分页参数不构成有效搜索。
- 关键词在标题、正文、标签、地点名称和地址任一字段中作字面包含匹配，忽略大小写，% 和 _ 按普通字符转义；不分词、不使用自然语言或语义推断。
- 关键词条件与结构化筛选为 AND，无关键词时仅做条件搜索；不搜索参与人员。
- 先按当前资格限制候选，再匹配和去重；无权内容不得进入结果及摘要；返回字段和排序分页与浏览一致。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | EMPTY_SEARCH | 关键词及业务筛选条件均为空 |
| 400 | INVALID_PARAMETER | 条件或游标无效 |

### 获取瞬间详情

GET `/moment/moments/{momentId}` 受保护接口

返回最新原内容及当前可访问关联，同时用于确认发布是否生效。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 获取瞬间详情 |
| 请求方式 | GET |
| 请求路径 | `/moment/moments/{momentId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |

#### 请求示例

```http
GET /moment/moments/019a48d0-0000-7000-8000-000000000002
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 UUIDv7 |
| author | object | 公开作者资料，仅 id、nickname、avatarFileId、avatarContentPath；不含 username |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].contentPath | string | GET /moment/files/{fileId}/content；每次读取校验权限 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete / canManageVisibility | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；内容与可见范围共用版本 |

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
      "avatarContentPath": null
    },
    "title": "海边的一天",
    "body": "今天一起看海。",
    "images": [],
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
    "canManageVisibility": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 每次检查原记录、当前成员与分享关系，不信任列表或通知曾经可见。
- 作者取得全部有效来源；非作者只取得自身有权进入的来源；时光引用按当前时光资格过滤，不能从时光分享反向获得原瞬间读取资格。
- 返回 ETag；权限失效或原记录已删除不返回旧正文、图片及来源名称。
- 发布结果查询只反映当前资源存在与内容，不返回请求处理中或历史失败状态。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 404 | RESOURCE_NOT_FOUND | 记录不存在、已删除或无权访问 |

### 编辑瞬间

PATCH `/moment/moments/{momentId}` 受保护接口

部分更新作者本人的原内容，可一并调整同行和圈子范围。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 编辑瞬间 |
| 请求方式 | PATCH |
| 请求路径 | `/moment/moments/{momentId}` |
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

```json
{
  "body": "傍晚又一起看了日落。",
  "tags": [
    "旅行",
    "日落"
  ]
}
```

请求头：`If-Match: "2026-11-03T02:00:00.000Z"`。

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 UUIDv7 |
| author | object | 公开作者资料，仅 id、nickname、avatarFileId、avatarContentPath；不含 username |
| title / body | string / null | 标题、普通文本正文 |
| mood | string / null | 心情，仅为 开心、平静、松弛、心动、惊喜、自在、低落 之一；未设置为 null |
| images | array<object> | 按 sortOrder 升序排列 |
| images[].fileId | string | 文件 UUID |
| images[].sortOrder | integer | 从 1 开始，连续且不重复 |
| images[].contentPath | string | GET /moment/files/{fileId}/content；每次读取校验权限 |
| images[].mimeType | string | 实际图片类型 |
| tags | array<string> | 标签名称 |
| memoryTime | string | 发生时间，UTC |
| location | object / null | 地点，子字段与发布参数一致 |
| sources | array<object> | 当前用户有权访问的同行和直接分享圈子；作者返回全部有效关联 |
| sources[].type | string | journey 或 circle |
| sources[].id / sources[].name | string | 来源 ID、名称 |
| sources[].entryPath | string | 同行 /gathering/journeys/{id}；圈子 /circle/circles/{id}，由客户端路由映射；目标 API 不在本次范围 |
| collections | array<object> | 当前有权访问的时光引用，元素为 id、name、entryPath（/album/collections/{id}）；不授予原瞬间访问权 |
| isOwn / canEdit / canDelete / canManageVisibility | boolean | 当前用户操作提示，服务端仍逐次校验 |
| createdAt / updatedAt | string | 发布时间、版本凭证；内容与可见范围共用版本 |

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
      "avatarContentPath": null
    },
    "title": "海边的一天",
    "body": "傍晚又一起看了日落。",
    "images": [],
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
    "canManageVisibility": true,
    "createdAt": "2026-11-03T02:00:00.000Z",
    "updatedAt": "2026-11-03T02:00:00.001Z"
  }
}
```

#### 业务规则

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

### 删除瞬间

DELETE `/moment/moments/{momentId}` 受保护接口

作者确认删除原瞬间，使所有入口立即停止展示。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 删除瞬间 |
| 请求方式 | DELETE |
| 请求路径 | `/moment/moments/{momentId}` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |
| If-Match | string | 是 | Header；双引号包裹最近 updatedAt |

#### 请求示例

```http
DELETE /moment/moments/019a48d0-0000-7000-8000-000000000002
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

### 获取瞬间可见范围

GET `/moment/moments/{momentId}/visibility` 受保护接口

作者读取当前关系，以便预览和修改范围。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 获取瞬间可见范围 |
| 请求方式 | GET |
| 请求路径 | `/moment/moments/{momentId}/visibility` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |

#### 请求示例

```http
GET /moment/moments/019a48d0-0000-7000-8000-000000000002/visibility
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 ID |
| journeyId | string / null | 关联同行 ID |
| circleIds | array<string> | 全部有效直接分享圈子 ID |
| sources | array<object> | 来源，字段同瞬间详情 |
| onlyMe | boolean | 无有效同行及圈子来源时 true |
| updatedAt | string | 与瞬间内容共用版本 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "journeyId": "019a48d0-0000-7000-8000-000000000004",
    "circleIds": [],
    "sources": [
      {
        "type": "journey",
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
      }
    ],
    "onlyMe": false,
    "updatedAt": "2026-11-03T02:00:00.000Z"
  }
}
```

#### 业务规则

- 仅作者可访问；非作者在详情中读取自己的合法 sources。
- 有效范围按当前关系和成员资格计算，不把参与人员或时光引用视为授权。返回 ETag。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 403 | FORBIDDEN | 可读取瞬间但非作者 |
| 404 | RESOURCE_NOT_FOUND | 瞬间不可访问 |

### 替换瞬间关联与分享

PUT `/moment/moments/{momentId}/visibility` 受保护接口

整体替换同行及圈子目标集合，支持首次分享、追加、更换和撤销。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 替换瞬间关联与分享 |
| 请求方式 | PUT |
| 请求路径 | `/moment/moments/{momentId}/visibility` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| momentId | string | 是 | Path；瞬间 UUID |
| If-Match | string | 是 | Header；双引号包裹最近 updatedAt |
| journeyId | string / null | 是 | 最终同行 ID；null 表示不关联 |
| circleIds | array<string> | 是 | 最终圈子 ID 集合；[] 表示全部撤销，最多 100 个且不重复 |

#### 请求示例

```json
{
  "journeyId": "019a48d0-0000-7000-8000-000000000004",
  "circleIds": [
    "019a48d0-0000-7000-8000-000000000005"
  ]
}
```

请求头：`If-Match: "2026-11-03T02:00:00.000Z"`。

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 瞬间 ID |
| journeyId | string / null | 关联同行 ID |
| circleIds | array<string> | 全部有效直接分享圈子 ID |
| sources | array<object> | 来源，字段同瞬间详情 |
| onlyMe | boolean | 无有效同行及圈子来源时 true |
| updatedAt | string | 与瞬间内容共用版本 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000002",
    "journeyId": "019a48d0-0000-7000-8000-000000000004",
    "circleIds": [
      "019a48d0-0000-7000-8000-000000000005"
    ],
    "sources": [
      {
        "type": "journey",
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "entryPath": "/gathering/journeys/019a48d0-0000-7000-8000-000000000004"
      },
      {
        "type": "circle",
        "id": "019a48d0-0000-7000-8000-000000000005",
        "name": "好友圈",
        "entryPath": "/circle/circles/019a48d0-0000-7000-8000-000000000005"
      }
    ],
    "onlyMe": false,
    "updatedAt": "2026-11-03T02:00:00.001Z"
  }
}
```

#### 业务规则

- 以整个目标集合为准，不采用增量含义。追加分享须先读取范围、合并旧圈子并由用户确认最终集合。
- 最多一个同行；已结束同行仍有效。对全部保留和新增目标校验作者成员资格；任一目标不可用时整批回滚，errors 仅包含本次请求提交的问题目标 ID，不泄露无权目标名称。
- 关系唯一键防重；已存在的有效分享保持 shared_at，不重复刷动态；恢复软删除分享复用原行并刷新 shared_at 和 sharer_user_id，再生成一次对应动态。
- 撤销关系不删除原内容；仍有其他来源的用户继续可读，无有效来源的非作者失权。移除旧目标不要求作者仍有其成员资格。
- 锁主记录并校验 If-Match，更新关系和主记录版本，在同一事务落库；保持原内容和 createdAt。结果未知读取可见范围核对，不盲目覆盖。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 409 | VERSION_CONFLICT | 内容或关系版本已变化 |
| 409 | TARGET_UNAVAILABLE | 任一目标失效或不是有效成员 |
| 403 | FORBIDDEN | 可读瞬间但非作者 |

### 查询可选关联与分享目标

GET `/moment/visibility-targets` 受保护接口

提供发布或管理范围所需的本人有效同行、圈子选项。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 查询可选关联与分享目标 |
| 请求方式 | GET |
| 请求路径 | `/moment/visibility-targets` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| type | string | 是 | Query；journey 或 circle |
| keyword | string | 否 | Query；名称包含匹配，最多 128 字符 |
| cursor | string | 否 | Query；上一页 nextCursor，首屏不传 |
| limit | integer | 否 | Query；默认 20，1～100 |

#### 请求示例

```http
GET /moment/visibility-targets?type=journey&limit=20
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | array<object> | 当前用户有效成员关系对应的未解散空间 |
| items[].id / items[].name | string | 空间 ID、名称 |
| items[].type | string | journey 或 circle |
| items[].status | integer / null | 同行 1 进行中、2 已结束；圈子 null |
| nextCursor | string / null | 下一页游标 |
| hasMore | boolean | 是否有下一页 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "items": [
      {
        "id": "019a48d0-0000-7000-8000-000000000004",
        "name": "毕业旅行",
        "type": "journey",
        "status": 1
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

#### 业务规则

- 只返回本人有效空间，不返回成员列表、邀请码或他人空间。按 id DESC 键集分页。
- 选项仅供展示，不能替代提交时事务内的资格校验；无目标返回空列表，引导由客户端处理。
- 最终分享范围为所选空间的有效成员，成员名单以空间模块为准，本接口不承诺固定人数快照。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_PARAMETER | type 或游标无效 |

### 上传瞬间图片

POST `/moment/files` 受保护接口

接收图片，完成校验及对象存储写入后建立 media_file 记录。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 上传瞬间图片 |
| 请求方式 | POST |
| 请求路径 | `/moment/files` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 201 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file | binary | 是 | multipart/form-data；仅一张 JPEG、PNG 或 WebP 图片；最大 10 MiB，最大 4000 万像素 |

#### 请求示例

```http
POST /moment/files
Content-Type: multipart/form-data; boundary=<boundary>

--<boundary>
Content-Disposition: form-data; name="file"; filename="photo.jpg"
Content-Type: image/jpeg

<图片二进制>
--<boundary>--
```

#### 响应数据

以下字段均位于成功响应的 data 内。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 新建 media_file ID |
| mimeType | string | 实际识别的 MIME 类型 |
| byteSize | integer | 实际字节数 |
| contentPath | string | 受保护读取路径 |

#### 响应示例

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "id": "019a48d0-0000-7000-8000-000000000003",
    "mimeType": "image/jpeg",
    "byteSize": 204800,
    "contentPath": "/moment/files/019a48d0-0000-7000-8000-000000000003/content"
  }
}
```

#### 业务规则

- 依据文件签名和实际解码校验，拒绝伪造类型及损坏图片；不采用客户端提供的文件名作为存储键。
- owner_user_id 由 JWT 决定；对象写入且检查成功后才建立文件记录。记录失败清理孤立对象，失败不返回文件 ID。
- 无上传任务或预签名上传接口；上传超时可重传，可能生成多个文件记录，不承诺上传幂等；客户端仅使用已确认返回的 ID。
- 引用提交失败不自动删除文件。仅无任何有效引用的文件可由服务端清理；引用建立与清理须串行校验文件有效性。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 400 | INVALID_IMAGE | 图片损坏、尺寸超限或无法解码 |
| 413 | PAYLOAD_TOO_LARGE | 图片超过 10 MiB |
| 415 | UNSUPPORTED_MEDIA_TYPE | 实际格式不支持 |
| 503 | STORAGE_UNAVAILABLE | 存储写入或校验失败 |

### 读取瞬间图片

GET `/moment/files/{fileId}/content` 受保护接口

校验当前读取资格后代理输出图片二进制。

#### 基本信息

| 属性 | 内容 |
| --- | --- |
| 接口名称 | 读取瞬间图片 |
| 请求方式 | GET |
| 请求路径 | `/moment/files/{fileId}/content` |
| 认证要求 | JWT Bearer Token |
| 成功状态码 | 200 |

#### 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| fileId | string | 是 | Path；media_file UUID |

#### 请求示例

```http
GET /moment/files/019a48d0-0000-7000-8000-000000000003/content
Authorization: Bearer <accessToken>
```

#### 响应数据

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| 响应正文 | binary | 原图片字节，不使用 JSON 包装 |
| Content-Type | Header | 实际图片 MIME 类型 |
| Content-Length | Header | 实际字节数 |
| Cache-Control | Header | private, no-store |

#### 响应示例

```http
HTTP/1.1 200 OK
Content-Type: image/jpeg
Cache-Control: private, no-store

<图片二进制>
```

#### 业务规则

- 上传者可以读取自己的有效文件用于草稿预览；其他用户必须对该文件所引用的至少一条未删除瞬间拥有当前读取资格。移除某处引用不影响其他合法用途；作者本人因其他用途仍可读取文件，不代表已删除瞬间可以访问。
- 文件存在不等于可访问；校验引用和权限后才输出正文。错误响应仍为 application/json。
- 不发放 MinIO 永久地址或可绕过权限的下载链接，不支持公开缓存。权限撤销不能回收已下载数据，客户端在失权后清除本地缓存。

#### 异常处理

| HTTP 状态码 | 业务错误码 | 场景 |
| --- | --- | --- |
| 404 | RESOURCE_NOT_FOUND | 文件不存在、已删除或当前无读取资格 |
| 503 | STORAGE_UNAVAILABLE | 合法文件对象暂时无法读取 |

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
