# 旁注 Android

基于 [jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis) 的个人维护版。
当前源码版本：`1.4-personal.96`。保留原项目 LICENSE、NOTICE 及第三方许可。

支持聊天页可见文字识别、微信 OCR、语音转写归属核对、联系人本地记忆、旧消息补录、
关系档案、回复建议、开场与邀约建议。消息由用户确认后发送，软件不自动发送。

关系优先级为联系人专属描述、已接受关系档案、全局默认关系。
模型刷新保留人工维护的档案条目，人工补充与聊天证据分别标注。
普通分析并行生成初稿和 Jev 判断，随后排序；开场使用回复接口；邀约先判断再起草。
设置中的接口地址、模型和密钥由使用者自行配置。

## 源码结构

- `app/src/main`：Android 界面、悬浮窗、采集、记忆和模型接口。
- `app/src/test`：本地回归测试；可选私有审计默认跳过，不提供私有数据。
- `app/src/androidTest`：设备验证代码。
- `tools/jev`：原项目模型调用与校准工具。
- `gradle`、`gradlew`、`gradlew.bat`：构建工具。

## 构建

使用 JDK 17 和 Android SDK，设置 `JAVA_HOME`、`ANDROID_HOME`，或自行创建本机的 `local.properties`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

安装包输出：`app/build/outputs/apk/debug/app-debug.apk`。
更换电脑或签名密钥后，不保证可以覆盖安装旧的个人测试包；自行保管签名材料。

## 数据与同步

仓库只保存源码、构建配置、测试与许可，不包含个人聊天数据库、关系档案、API 密钥、
签名私钥、手机截图、本地测试报告或旧版安装包。本地私人开发笔记不上传，测试中的联系人示例已匿名化。
这是开发目录的发布镜像；后续更新应从原开发目录运行本机同步脚本，勿在镜像目录直接修改源码。
每次同步形成新的 Git 提交，可查看差异与历史；不会强制覆盖远端分支。
