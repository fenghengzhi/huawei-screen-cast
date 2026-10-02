# GitHub Actions 发布

主分支提交和 Pull Request 会自动编译调试 APK、执行单元测试及 Android Lint。版本标签 `v*` 在上述检查通过后构建签名 Release APK，并发布到 GitHub Releases。

## 签名配置

在仓库 Settings → Secrets and variables → Actions 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 发布签名别名 |
| `ANDROID_KEY_PASSWORD` | 签名私钥密码 |

本次发布已创建并配置这些 Secret。用于恢复的本机备份位于项目根目录 `.signing/`，已加入忽略规则，不会提交到公开仓库。请在自己的安全存储中备份；后续覆盖更新必须保留同一签名证书。

私钥仅在发布 job 中还原到 runner 临时目录，任务结束时删除。APK 内包含完整第三方许可文本，Release 同时附带许可声明和 SHA-256 校验文件。

兼容镜像音频使用原生 FDK-AAC 编码器，构建固定使用 NDK `28.2.13676358` 和 CMake `3.22.1`。工作流会安装这两个组件。APK 的 `assets/licenses` 同时包含 FDK 的完整许可和完整源码压缩包；发布时不要删除这些材料。FDK 软件许可不包含 AAC 专利许可。

## 发布新版本

1. 修改 `android/app/build.gradle` 中的 `versionName` 和递增的 `versionCode`。
2. 更新 `RELEASE_NOTES.md` 并提交到主分支。
3. 在对应提交创建并推送匹配 `versionName` 的版本标签，例如：

```sh
git tag -a v1.3.1 -m 'Release v1.3.1'
git push origin v1.3.1
```

标签与 APK 的版本号不匹配时发布会失败。必要时可在 Actions 手动选择已有版本标签重新运行；不选择标签时只执行构建检查。

## 本地签名构建

设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后运行：

```sh
cd android
./gradlew :app:assembleRelease
```

没有签名环境变量时，`assembleRelease` 只生成 unsigned APK；日常本地调试继续使用 `assembleDebug`。
