# 保留策略演算台

一个本地、可重放的数据保留演算工具。资源只保存测试元数据与内容摘要，不连接外部对象存储；所有状态保存在 SQLite。执行删除前先冻结策略版本、资源快照摘要和逐条解释，执行器使用幂等令牌并保留可核验收据。

## 构建与演示

```bash
./gradlew classes
./gradlew test
./gradlew run --args='--port 5210'
```

浏览器打开 <http://localhost:5210/>，页面标题和正文包含“保留策略演算台”。首次启动会创建 `data/retention.db` 与三个内置情景：

- `R-CONFLICT`：多个规则命中，`RULE-LOG-SPECIFIC` 因显式优先级获胜，但同优先级会取最大保留天数。
- `R-NEW-HOLD`：计划冻结后新增法律保留，冻结解释仍显示当时无保留，执行前检查将条目阻止并写入新原因。
- `R-INTERRUPT`：执行器在写出 `UNKNOWN` 收据后模拟退出；重启或直接恢复都会先拒绝重做，必须调用核验端点。

预置计划因此展示为：一项 `DELETED`、一项 `BLOCKED`、一项 `UNKNOWN`，等待核验。

## 受控时钟

默认时钟固定为 `2026-09-29T00:00:00Z`。

- `POST /api/clock`：`{ "now": "2026-09-30T00:00:00Z" }`
- `POST /api/clock/advance`：`{ "duration": "P1D" }`
- 请求头 `X-At-Time`：只覆盖创建、冻结或评估请求的业务时间；法律记录仍在追加时保存创建时刻。

所有时间必须是 UTC ISO-8601 instant，例如 `2026-09-20T00:00:00Z`。

## HTTP API 规则

JSON 请求发送 `Content-Type: application/json`。路径参数需要 URL 编码；成功创建返回 `201`，常规读取返回 `200`，冲突返回 `409`，未知资源返回 `404`。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /` | 页面 |
| `GET /api/clock` / `POST /api/clock` / `POST /api/clock/advance` | 可控时钟 |
| `GET /api/resources` / `POST /api/resources` | 资源列表、录入资源 |
| `GET /api/resources/{id}` / `PUT /api/resources/{id}` | 当前资源、生成新版本 |
| `GET /api/resources/{id}/trace` | 资源到规则、保留、计划、收据的追踪 |
| `GET /api/resources/{id}/holds` / `POST /api/resources/{id}/holds` | 资源保留事件 |
| `GET /api/holds?resourceId={id}` | 保留事件查询 |
| `GET /api/policies` / `POST /api/policies` / `GET /api/policies/{id}` | 版本化策略 |
| `GET /api/plans` / `POST /api/plans` / `GET /api/plans/{id}` | 删除计划 |
| `POST /api/plans/{id}/approve` | 乐观版本批准并创建唯一执行代次 |
| `POST /api/plans/{id}/pre-execution-checks` | 只执行执行前法律保留/快照漂移检查 |
| `POST /api/plans/{id}/executions` | 执行、选择条目、模拟退出 |
| `GET /api/executions/{id}` | 执行代次和收据 |
| `POST /api/plan-items/{id}/verify` | 核验 `UNKNOWN` 条目 |

资源字段：`id`、`name`、`category`、`createdAt`、`lastAccessedAt`、`tags`、`contentSummary`。

策略规则字段：

- `ruleId`、`category`、`priority`、`retentionDays` 为必填。
- 可选：`requiredTags`、`anyTags`、`minAgeDays`、`maxAgeDays`、`minLastAccessAgeDays`、`maxLastAccessAgeDays`。
- `priority` 数值越小优先级越高；只在胜出的同优先级组内取最大 `retentionDays` 作为最严格结果。

法律保留事件字段：`eventType` 为 `HOLD` 或 `RELEASE`，并带 `effectiveFrom`、可选 `effectiveTo`、`targetHoldId`、`reason`。记录只能追加；数据库触发器拒绝更新和删除。解除保留应追加新的 `RELEASE` 事件。

## 冻结、冲突与恢复

计划创建时会保存：

- 策略版本 ID、版本号和策略指纹；
- 每个资源的版本 ID、资源快照指纹和快照 JSON；
- 初始处置 `DELETABLE`、`RETAIN_POLICY`、`RETAIN_HOLD` 或 `RETAIN_NO_RULE`；
- 所有规则的逐项匹配条件、胜出优先级、最严格保留天数和自然语言原因。

批准体示例：

```json
{
  "expectedPlanVersion": 1,
  "operator": "alice",
  "idempotencyKey": "approval-20260929-001"
}
```

两个操作者同时批准时，数据库只创建一个执行代次：相同幂等键返回已有代次，另一个键返回 `409`。批准成功会把计划版本从 1 增到 2；仍提交 `expectedPlanVersion: 1` 的陈旧请求返回 `409`。

执行体支持：

```json
{
  "idempotencyKey": "run-001",
  "runAt": "2026-09-29T00:00:00Z",
  "itemIds": ["R-INTERRUPT"],
  "crashAfterItemId": "R-INTERRUPT"
}
```

条目先变为 `UNKNOWN` 并写入 `PROCESSING` 收据；只有删除账本提交后才变为 `DELETED`。模拟退出后不能直接重新执行，必须调用核验：

```json
{ "requestToken": "verify-001" }
```

核验会读取删除账本：账本存在则确认 `DELETED`；账本不存在则把结果标记为 `NOT_CONFIRMED`，条目回到 `PENDING`，之后才允许重新处理。

## 数据

默认数据库是 `data/retention.db`。要从干净状态演示，可传一个新路径：

```bash
./gradlew run --args='--port 5210 --db /tmp/retention-fresh/retention.db'
```
