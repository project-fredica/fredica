---
title: fredica-pyutil 引入 Pyright 类型检查
order: 540
---

# fredica-pyutil 引入 Pyright 类型检查

> 为 Python 子服务引入 [Pyright](https://github.com/microsoft/pyright) 静态类型检查，与前端 `tsc --noEmit` 和 Kotlin `./gradlew :shared:build` 形成统一的类型安全保障。

---

## 1. 背景

fredica-pyutil 代码已有较好的类型注解基础：

- `TypedDict`：`BilibiliSubprocessContext`、`TaskEndpointCommand` 等
- `dataclass`：`DeviceGpuInfo`、`CudaDevice` 等
- `Literal`：`TaskEndpointCommandName`
- Pydantic `BaseModel`：路由请求体

但缺少静态类型检查工具，类型错误只能在运行时暴露。Pyright 是 Microsoft 维护的 Python 类型检查器，性能优异，支持最新 Python 版本（含 3.14），且可通过 `pip install pyright` 直接使用。

---

## 2. 配置方案

### 2.1 pyrightconfig.json

位置：`desktop_assets/common/fredica-pyutil/pyrightconfig.json`

```json
{
  "include": ["fredica_pyutil_server", "tests"],
  "exclude": ["**/__pycache__", "**/node_modules"],
  "pythonVersion": "3.14",
  "pythonPlatform": "Windows",
  "typeCheckingMode": "basic",
  "reportMissingTypeStubs": "none",
  "reportWildcardImportFromLibrary": "warning",
  "reportMissingImports": "warning"
}
```

### 2.2 模式选择：basic

| 模式 | 适用场景 | 本项目考量 |
|------|---------|-----------|
| off | 仅语法检查 | 太弱 |
| **basic** | 核心类型错误 | ✅ 当前选择 |
| standard | 更严格的类型推断 | 目标演进 |
| strict | 完整类型覆盖 | 远期目标 |

选择 basic 的理由：

1. **第三方库缺少类型存根**：`bilibili-api-python`、`curl_cffi`、`pynvml`、`huggingface_hub` 均无完整 py.typed 支持，standard/strict 会产生大量 `reportMissingTypeStubs` 噪音
2. **存量 wildcard import**：`task_endpoint_util.py` 使用 `from typing import *`，需逐步清理
3. **渐进式引入**：basic 已覆盖赋值不兼容、参数类型不匹配、未定义变量等核心错误，足够作为起步

### 2.3 依赖安装

在 `requirements.txt` 追加：

```
pyright
```

pyright pip 包内置 Node.js runtime（约 40MB），无需额外安装 Node.js。

---

## 3. 运行命令

```shell
cd desktop_assets/common/fredica-pyutil

# 完整类型检查
../../windows/lfs/python-314-embed/python.exe -m pyright

# 检查单个文件
../../windows/lfs/python-314-embed/python.exe -m pyright fredica_pyutil_server/util/device_util.py

# 输出 JSON 格式（适合 CI 解析）
../../windows/lfs/python-314-embed/python.exe -m pyright --outputjson
```

---

## 4. 已知需修复的问题

| 文件 | 问题 | 修复方式 |
|------|------|---------|
| `util/task_endpoint_util.py` | `from typing import *` | 改为显式导入 |
| 各 bilibili worker | 返回类型为 `dict` 缺少具体类型 | 可选：添加 TypedDict 或保持 basic 模式下不报错 |

---

## 5. 演进路径

```
Phase 1 (当前)     Phase 2           Phase 3
─────────────────────────────────────────────────
basic 模式         standard 模式      strict (核心模块)
修复 error 级别    修复 warning 级别   添加第三方 stub
                   清理 wildcard      util/ 目录 strict
```

### Phase 2：升级到 standard

- 清理所有 `from typing import *`
- 为工具函数添加完整返回类型注解
- 处理 `reportUnknownParameterType` 等新增诊断

### Phase 3：核心模块 strict

- 在 `pyrightconfig.json` 的 `strict` 字段中指定核心路径：
  ```json
  "strict": ["fredica_pyutil_server/util"]
  ```
- 为常用第三方库创建 `typings/` 目录下的自定义 stub

### 可选：CI 集成

```yaml
- name: Pyright type check
  run: |
    cd desktop_assets/common/fredica-pyutil
    pip install pyright
    pyright --outputtype=github
```

---

## 6. 参考

- [Pyright 配置文档](https://microsoft.github.io/pyright/#/configuration)
- [Pyright GitHub](https://github.com/microsoft/pyright)
- 本项目测试指南：`docs/dev/testing.md`
