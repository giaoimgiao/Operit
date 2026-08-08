# UGIRL 免费CDN · 本地版

免费无限静态 CDN（基于 prod.ugirl.ai 存储通道），零服务器零成本。

## 运行

```bash
python3 ugirl_cdn_app.py          # 启动后自动打开浏览器 http://127.0.0.1:8866
UG_APP_PORT=9000 python3 ugirl_cdn_app.py
```

或使用打包版（GitHub Actions 产物 / dist 目录）。

## 功能

- 🆕 一键开新容器：自动注册账号（免邮箱验证），每个账号 = 独立容器（文件互不可见、互不可删）
- 🔑 Token 直连：粘贴已有 ugirl token 即用
- 📁 拖拽上传：浏览器 XHR 直传 R2，真实进度条
- 🌐 网页资源提取：输入网页 URL → 提取全部静态资源 → 单个/全部转存到 CDN
- 📥 强制下载：任意文件一键下载回本地
- 👤 多容器管理：一键切换

## 构建

```bash
pip install pyinstaller
pyinstaller --onefile --name ugirl-cdn-app ugirl_cdn_app.py
```

GitHub Actions 自动构建三平台（Windows/Linux/macOS），见 `.github/workflows/build.yml`。

## 说明

Token 仅存于本机（ugirl_app_accounts.json + 浏览器 localStorage）。删除接口有所有权校验，容器间天然隔离。