# YOU Flutter 客户端

依据 `../doc/YOU-TRD.md` 与 `../prototype/` 建立，目标 Android / iOS。

## 运行

需要 Flutter 3.44.8 / Dart 3.12.2 或兼容新版 SDK。提交 pubspec.lock 固定依赖。

```powershell
cd you-frontend
flutter pub get
dart run build_runner build
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Android 模拟器使用 10.0.2.2 访问宿主机；真机改为可达的局域网地址。正式环境传 HTTPS 地址。Android 仅 debug 允许 HTTP。iOS 在 macOS 上编译，开发 HTTP 需自行配置限域 ATS 例外或使用 HTTPS。

## 当前范围

- View + Riverpod ViewModel + Repository 分层，按 TRD 预留业务目录。
- 四分支 StatefulShellRoute.indexedStack，公共 76px 品牌栏、原型 Hugeicons 图标、胶囊导航、根栈发布及详情入口。
- 主题 token 与加载、空、错误组件。原型既有颜色与设备字体优先，不分发 Apple 字体。
- 登录页面调用 POST /auth/login，凭证写入安全存储；启动恢复凭证，未登录深链携带目标，登录后恢复；401 清理凭证并跳转登录。无刷新接口。
- Dio 从环境读取地址，有超时与 Bearer 认证；请求发送时读取当前凭证。
- Drift SQLite 草稿表以 ownerId + draftId 为主键；数据层必须明确传账号 ID，草稿按账号查询。数据库按需初始化，当前占位页面尚不写入草稿。
- 用户模块接入注册、登录、本人资料读取及本机退出。媒体数据层接入预签名地址申请与 PUT 上传。编辑器、找回、任务等由后续模块完成。当前没有本地假登录或演示业务数据；运行四 Tab 需要后端有效账号。
- api_generated 仅用于未来从已校验 OpenAPI 生成代码；Markdown 契约不伪装成生成客户端。

## 目录

`app/` 组装、路由、主题与主框架；`core/` 认证、网络、存储、错误及平台适配；`shared/` 通用组件和值类型；`features/` 按业务组织页面、ViewModel、数据层和模型。

## 应用日志

业务代码统一调用 `AppLogger`，新增事件时在 `AppLogEvent` 中登记固定标识。标识按 `业务模块.业务动作.事件结果` 命名，例如 `auth.register.started`、`network.request.failed`、`storage.upload.failed`。事件标识不得包含用户输入、UUID 或其他运行时值。

| 级别 | 方法 | 适用场景 |
| --- | --- | --- |
| DEBUG | `debug` | 开发调试、内部执行细节 |
| INFO | `info` | 关键业务事件与操作结果 |
| WARNING | `warning` | 可恢复异常、重试、降级 |
| ERROR | `error` | 请求失败、功能执行失败 |
| FATAL | `fatal` | 严重且不可恢复的应用故障 |

`AppLogger` 通过 Talker 输出到控制台，FATAL 映射到 Talker 的 critical 级别。错误日志保留异常类型和可用的堆栈。请求头、请求体、签名 URL 和异常原文不传入 Talker。

## 验证

```powershell
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
```

修改数据库表后运行 `dart run build_runner build`，同步提交生成的 `.g.dart`，并为后续版本添加迁移。当前路由中的详情、编辑等均为框架占位页，不承诺已实现原型业务交互。

图标复制自仓库原型 `prototype/assets/hugeicons`（Hugeicons Free Stroke Rounded）；字体使用系统回退。

## 用户与媒体连调

当前后端的 OpenAPI 由本地服务的 /v3/api-docs 提供。注册使用 POST /auth/register，登录使用 POST /auth/login，个人资料使用 GET /users/me。退出会清除本机安全存储中的令牌。资料卡默认森林封面来自仓库原型使用的 Unsplash 图片。昵称、签名、封面和已上传头像预览按账号保存在本机应用目录，供资料编辑接口上线前使用。头像通过真实 PUT 上传，服务端头像绑定接口上线前由本机预览提供回显。

媒体上传向 POST /media/uploads 发送 MIME 类型与字节数，再使用独立 Dio 向返回的签名 URL 发起 PUT。图片限 JPEG、PNG、WebP 和 10 MiB。签名 URL 的主机必须从客户端可达，签名后需保留原始地址。

真实 API 测试默认跳过。启动连接仓库本地 Compose 数据库的后端后，在前端目录运行：

```powershell
./tool/test-api.ps1
```

测试使用真实 API 验证注册、登录、资料读取、认证失败和预签名地址申请，在客户端拦截 PUT 检查请求，避免留下存储对象。脚本会清理测试账号。若测试中断，可在前端目录执行 `python tool/cleanup_api_fixtures.py` 清理。
