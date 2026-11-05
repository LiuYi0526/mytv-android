# 流光 TV 发布指南

## 版本与分支

- `main` 为长期主线，`feature/ui` 保留为历史分支。
- 在 `version.properties` 中修改 `versionName` 和 `versionCode`，后者每次发布递增。
- 本次候选构建为 `1.1.0` / `110`；发布前完成正式 APK 真机验证。
- 旧上游标签归档为 `legacy/upstream-*`；流光 TV 使用 `v<versionName>`，已发布的标签不移动、不复用。
- 版本说明放在 `docs/release-notes/<versionName>.md`；发布时给审核过的提交创建 `v<versionName>` 标签。
- 本地未提交的构建会在 `build-info.json` 中标记 `workingTreeDirty: true`，不能冒充已标记的源码版本。

## 本地签名与备份

正式签名默认为用户目录下的 `.android/keystores/liuguang-tv/key.properties`。
也可通过 `LIUGUANG_SIGNING_PROPERTIES` 指定仓库外的配置文件，或使用 `KEYSTORE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量。
`storeFile` 相对于签名配置文件目录解析。密钥和密码不进入 Git。

公钥证书在 `docs/release-certificate.pem`，可用于核对发布包签名；不包含私钥。
首次生成的私钥为 RSA 3072 位、有效期约 30 年。生成后已核对两份本地备份，但同一电脑上的两份文件不能替代离线备份。
请把签名目录完整复制一份到你自己的离线介质，保留其中的 JKS 和密码文件。不要重新生成证书来替换旧版本的更新签名。

## 本机构建

使用 JDK 17、Android SDK 36、Build Tools 36.0.0；AGP 和 Gradle 使用仓库固定版本。

```sh
./gradlew :tv:assembleOriginalRelease -PsplitApks=true
python3 scripts/release/prepare_release.py --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

Windows 使用 `gradlew.bat`，将 `--build-tools` 换成实际 SDK 目录；中文工作目录可能需要增加 `-Pandroid.overridePathCheck=true`。
构建会生成各 ABI 拆包及 universal；收集脚本只向 `dist/` 提供三份公开安装包：

- `liuguang-tv-<version>-armeabi-v7a.apk`
- `liuguang-tv-<version>-arm64-v8a.apk`
- `liuguang-tv-<version>-universal.apk`（ARM32、ARM64、x86、x86_64）

收集前检查签名是否匹配公钥证书、版本/包名/minSdk、非 debuggable、实际 ABI 与 64 位库的 16 KB 对齐。
同时保存 `SHA256SUMS.txt`、`build-info.json`、`stable.json`、更新说明及 `debug-symbols/mapping.txt`。

## GitHub Actions

仓库在 `main` 上提交工作流后，普通 push/PR 执行构建和单元测试，不读取签名密钥。
`Build signed release APKs` 通过 Actions 页面手动运行，选择 `main`。
该工作流仅生成和保存签名产物，不自动发布 Release，也不修改更新清单。

在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 同一份正式 JKS 的 Base64 内容 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 正式密钥别名，本次为 `liuguang-tv` |
| `KEY_PASSWORD` | 密钥密码 |

从仓库外的签名文件读取并配置 Secrets，不把它们写进工作流。运行后下载 `liuguang-tv-release` 与 `liuguang-tv-debug-symbols` 两个产物。
原生兼容 AAR 已包含在仓库中，常规构建不重新编译 FFmpeg 或 RTMP 原生代码。

## 发布顺序

1. 在签名后的 ARM 包及 universal 上完成验证，覆盖电视遥控、触摸、订阅/节目单、长时间播放及相关协议。16 KB 静态检查不等同于 16 KB 真机测试。
2. 确认新增应用的数据导入，以及同包名、同证书、递增版本号的覆盖升级路径。
3. 给对应源码提交打版本标签，在 GitHub Release 上传三份 APK、校验值及许可说明；不要上传私钥、密码或本机配置。
4. 从同次构建的 `dist/stable.json` 更新 `updates/stable.json`。下载地址与版本标签必须对应真实已发布产物。
5. 长期保存该版本的源码标签、构建信息和 R8 mapping，便于定位崩溃。

首版仅保留稳定通道。应用按系统 `SUPPORTED_ABIS` 选包：ARM 优先对应拆包，x86 系列回退到 universal。
GitHub 地址使用直连，不再强制经过原项目的第三方代理。问题反馈走本仓库 Issues。
