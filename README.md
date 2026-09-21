# Lockfile 语义合并室（Lockfile Semantic Merge Room）

一个纯 JDK 实现的本地 Web 应用，用于对自定义但真实可用的 lockfile 做 **三方语义合并**。
它不做文本行合并，而是把 base / left / right 三份内容解析成包图，基于图结构做
自动合并、冲突待决、根可达清理与完整性校验，并支持人工逐项裁决后重新验证。

## 构建与运行

```bash
# 构建检查
./gradlew classes

# 测试 + 演示
./gradlew test
./gradlew run --args='--host 127.0.0.1 --port 5238'
```

打开 <http://127.0.0.1:5238>，页面标题为 **Lockfile 语义合并室**。
点击「载入示例」可体验：删除根 + 传递升级、同名多版本、规范化平台条件、
逐项冲突裁决、可达性清理与稳定排序下载。

会话数据持久化在工作目录的 `.lockmerge-data/`（已 gitignore）。
重启进程后未完成会话（原始文本、诊断、裁决、输出版本）可继续。

## lockfile v1 格式

```text
lockfile v1
root web@1.0.0                      # 顶层根依赖，精确钉住 name@version

package web@1.0.0                   # 包节点 key = name + version
    source registry:acme/web        # 来源坐标 <type>:<coordinate>
    integrity sha256:aaaa1111       # 完整性摘要（sha256/384/512）
    platforms os=linux | os=macos   # 整节点平台条件（可选，布尔表达式）
    requires:                       # 对子节点的精确引用
        lib@1.0.0                   # 无条件边
        lib@1.0.0 when arch=arm64   # 条件边（when + 布尔表达式）
    end                             # 结束 requires 并结束 package 块
```

- 缩进固定 4 空格一层；`#` 起始为注释；不允许 Tab。
- 同名不同版本是不同节点，可共存。
- 平台表达式语法：`|`（或）、`&`（与）、`!`（非）、括号、
  `true/false`、裸标签（`linux`）与比较（`os=linux`、`arch!=arm64`）。

## 语义规则

- **完整性**：按 `(source 坐标, version)` 分组；同一来源坐标 + 版本的节点
  摘要不一致即为 `INTEGRITY_MISMATCH`（即使包名不同、属于不同别名）。
- **删除根 + 传递升级**：一方删除根、另一方只升级该根的传递节点，**不算冲突**。
  合并后从保留的根做可达遍历，无根可达的节点被清理，并在「可达性清理说明」中
  解释（例如“仅能通过已删除根 tool 到达”）。
- **平台条件按语义比较**：表达式先规范化为吸收/矛盾化简后的 DNF 规范串，
  因此 `a | b` 与 `b | a`、`!!(a & b)` 与 `a & b`、
  `!(a | b)` 与 `!a & !b` 等价；比较从不基于原始字符串。
- **人工裁决后重验证**：选择某冲突版本后，重新从保留的根做可达遍历，
  重新检查所有父引用（`DANGLING_EDGE`）与根钉版（`DANGLING_ROOT`），
  不允许留下悬空节点；不闭合则不产出合并结果、禁止下载。
- **裁决指纹绑定**：每条裁决记录 base/left/right 三份输入的 SHA-256 指纹
  （以及三者的组合指纹）。任何一份输入变化，旧裁决不再自动套用，
  页面会把它标为“旧裁决已失效”，需要重新裁决。
- **输出**：仅当没有解析错误、未决冲突、悬空引用与完整性冲突时产出；
  包按 `name` 再 `version` 稳定排序、边按目标与条件排序，
  输出本身满足 parse-print-parse 等价。

## 并发编辑

所有写接口（更新输入、裁决、清除裁决）带乐观锁 `revision`；
基于旧修订的并发写入返回 HTTP 409 `CONCURRENT_EDIT`，客户端刷新后重试。

## HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/state` | 当前会话与最新评估（诊断/冲突/图/问题/输出） |
| POST | `/api/inputs` | 提交三份原文（body: `revision,base,left,right`） |
| POST | `/api/decide` | 对某冲突裁决（`revision,conflictId,optionId`） |
| POST | `/api/reset` | 清除全部裁决 |
| GET | `/api/download` | 下载稳定排序的 `merged.lock`（未闭合时 409） |

## 目录结构

```
src/main/java/lockmerge/
  platform/   平台条件词法/语法/DNF 规范化
  lock/       lockfile v1 解析器、规范打印器、输入指纹
  merge/      三方语义合并、冲突模型、可达清理、完整性/悬空检查
  session/    会话服务、乐观锁、JSON 文件原子持久化
  json/       最小 JSON 编解码
  web/        JDK 内置 HTTP 服务与视图映射
src/main/resources/web/  单页前端（三栏输入 / SVG 包图 / 裁决 / 检查 / 下载）
src/test/java/lockmerge/ 25 个 JUnit 5 测试
```
