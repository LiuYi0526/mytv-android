# 流光 TV Android 应用

使用方法、下载及架构选择见[项目首页](../README.md)。

- 最低版本：Android 6.0 / API 23。
- Release：`io.github.liuyi0526.mytv`。
- Debug：`io.github.liuyi0526.mytv.dev`。
- 构建任务：`:tv:assembleOriginalDebug`、`:tv:assembleOriginalRelease`。
- 正式拆包：在 Release 构建时增加 `-PsplitApks=true`。
- 签名及自动构建：[发布指南](../docs/RELEASING.md)。
