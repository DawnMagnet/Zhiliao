# 知了

知乎客户端的 LSPosed 模块，提供广告拦截与界面净化。运行于知乎进程内，通过 libxposed API 102 注入。

[![Download](https://img.shields.io/github/v/release/Piracola/Zhiliao?label=下载)](https://github.com/Piracola/Zhiliao/releases/latest)
[![Chat](https://img.shields.io/badge/Telegram-群-blue.svg?logo=telegram)](https://t.me/joinchat/OibCWxbdCMkJ2fG8J1DpQQ)
[![License](https://img.shields.io/github/license/Piracola/Zhiliao?label=License)](https://choosealicense.com/licenses/gpl-3.0/)

<p align="center">
  <img src="docs/screenshots/settings-light.png" width="220" alt="知了设置页（浅色）">
  <img src="docs/screenshots/settings-dark.png" width="220" alt="知了设置页（深色）">
  <img src="docs/screenshots/settings-dynamic.png" width="220" alt="知了设置页（壁纸取色）">
</p>

## 功能

**广告拦截**

- 启动页广告
- 信息流广告
- 浮层 / 彩蛋广告
- 回答列表广告
- 评论区广告
- 分享广告
- 回答底部广告
- 搜索结果广告

**界面净化**

- 不显示小红点
- 隐藏会员卡片
- 隐藏热点通知
- 隐藏置顶热门
- 精简文章页面
- 隐藏「我的」底部混合卡片
- 按需隐藏导航栏的首页、看山、发布、消息、我的
- 禁用活动主题

**内容过滤**

- 按关键词过滤视频、文章、想法
- 隐藏圈子
- 隐藏商品推荐
- 隐藏相关搜索
- 自定义过滤规则

**其他**

- 外部链接直接用浏览器打开（含 Ex）
- 反追踪
  - 反链接参数追踪：复制、分享出去的链接自动去掉 `utm_*`、`share_code`、`share_from` 等追踪参数，`link.zhihu.com` 这类转发短链会还原成真实地址
  - 反图片链接追踪：分享卡片的二维码改为本地生成，扫出来直接指向回答或文章本身，不再是 `s.zhihu.com` 短链
  - 拦截分享上报与全站埋点
- 禁止自动刷新：重新打开或切回知乎时，首页停在缓存内容上，不再被自动刷新顶掉（下拉刷新照常；冷启动后的内容会一直冻结，直到你主动刷新）
- 第三方登录
- 状态栏沉浸
- 禁止进入全屏模式
- 显示卡片类别
- 清理知乎临时文件
- 移除下一个回答按钮

**水印**

- 移除页面、视频、混合容器中的水印
- 图片换源去水印（实验性）
- 注入 JS 脚本
- 开启 WebView 调试

## 与上游版本的差异

本项目基于 [shatyuka/Zhiliao](https://github.com/shatyuka/Zhiliao) 继续维护，上游功能全部保留，并在此基础上做了以下调整：

- **设置独立成 App。** 不再向知乎注入设置页面，全部开关集中在知了 App 内。界面是 Material 3：跟随系统深色模式，Android 12 及以上可跟随壁纸取色，宽屏用导航栏分栏。
- **适配状态可见。** 知乎版本更新导致某个功能无法定位目标时，对应开关会置灰并标注「当前知乎版本暂不支持」，避免开关处于开启状态但实际无效。
- **支持诊断导出。** 设置页提供「自检」用于查询各功能的挂载情况，以及「导出诊断」用于将版本信息与开关状态整理为文本并复制到剪贴板。模块不申请网络权限，这些信息仅保存在本地。
- **跟进版本更新。** 维护侧每天获取知乎官方安装包，校验包名、版本与签名证书，并尝试执行兼容性检查，以便尽早发现新版本导致的功能失效。

## 安装

1. 安装支持 libxposed API 102 的框架（LSPosed 2.1.0 及以上）。
2. 安装知了 App。
3. 在 LSPosed 中启用知了，作用域勾选「知乎」。
4. 强制停止知乎后重新打开，或重启设备。
5. 打开知了 App 调整功能开关；修改后点击「重启知乎」生效。

## 兼容性

支持 Android 8.0 及以上。知乎 11.10.0（版本号 41012，小米渠道包）已实测 31 项功能全部可用；其他版本通常也可使用，无法适配的功能会将开关置灰。

## 下载

- [GitHub Release](https://github.com/Piracola/Zhiliao/releases/latest)
- [蓝奏云](https://wwa.lanzoux.com/b00tscbwd) 密码：1hax

## 常见问题

- **开关呈灰色**：当前知乎版本上未找到该功能对应的目标，暂时无法使用，等待后续更新。
- **修改设置后未生效**：点击设置页的「重启知乎」，或手动强制停止知乎后重新打开。
- **首页内容空白**：检查「过滤视频 / 文章 / 想法」是否开启过多——把带内容的卡片都过滤掉，首页就只剩空白了。

## 反馈

问题反馈请提交至 [GitHub Issues](https://github.com/Piracola/Zhiliao/issues)，也可在 Telegram 群中提出。附上「导出诊断」生成的文本有助于快速定位问题。

## 致谢

- [shatyuka/Zhiliao](https://github.com/shatyuka/Zhiliao)：本项目的上游，去广告与界面净化的核心思路与实现均来自原作者 shatyuka。
- [daxiaamu/Zhiliao](https://github.com/daxiaamu/Zhiliao)：同源的另一分支，本项目部分功能的实现思路与新版适配方式参考了该仓库。
- libxposed：模块与框架之间的接口标准（`io.github.libxposed:api`）。
- Jetpack Compose Material 3：设置 App 的界面。Android 12 及以上用系统壁纸取色，更早的系统用内置配色。
- [Lucide](https://lucide.dev)：设置页图标，ISC 许可。
- [DexKit](https://github.com/LuckyPray/DexKit)：用于定位代码的诊断工具。

## License

本项目使用 [GNU General Public Licence, version 3](https://choosealicense.com/licenses/gpl-3.0/) 许可。

设置图标由 [Lucide](https://lucide.dev) 生成，Lucide 使用 ISC 许可：

> ISC License
>
> Copyright (c) 2026 Lucide Icons and Contributors
>
> Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

