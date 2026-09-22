# Edge API Gateway（Java / Spring Boot 3）

一个可在本机独立运行的轻量边缘网关：按可配置规则把外部请求转发到**本机上游服务**，
并在所有入口上对「路由、限流、上游异常、配置热更新」给出**一致且可解释**的结果。

- 仅依赖 Spring Boot Web + JDK 内置 `java.net.http.HttpClient` / JDK 内置 `HttpServer`（测试）
- 端到端验证不依赖外部网络、不依赖真实凭据（网关与内置 demo 上游可运行在同一进程）

---

## 1. 快速开始

```bash
# 构建并运行（内置 demo 上游 + 演示路由配置，单进程自包含）
mvn -q package -DskipTests
java -jar target/edge-api-gateway-java-1.0-SNAPSHOT.jar --spring.profiles.active=demo

# 正常转发（v1 路由 -> 内置上游 /__upstream/...）
curl -s http://127.0.0.1:18080/api/v1/things -H 'X-Caller-Id: alpha'

# 运行全部测试（39 个用例，完全离线）
mvn test
```

关键配置项（`src/main/resources/application.properties`）：

| 配置 | 说明 |
|---|---|
| `server.port` | 网关监听端口（默认 18080） |
| `gateway.config.path` | 路由配置来源：外部文件路径或 classpath 资源名；留空使用 `gateway-routes.json` |
| `--spring.profiles.active=demo` | 启用内置演示上游（`/__upstream/**`） |

---

## 2. 路由模型与优先级

每条规则（`RouteConfig`）包含匹配条件与转发参数：

- `pathPrefix`：路径前缀（必须以 `/` 开头）
- `methods`：允许的 HTTP 方法白名单（空=任意）
- `requiredHeaders`：必须全部满足的请求头（`名称: 期望值`，值为 null 表示只要求存在）
- `upstream` / `prefixRewrite`：目标上游与前缀重写
- `priority`、`timeoutMillis`、`maxRetries`、两级限流、`allowedCallers`

### 2.1 确定性唯一选择

一个请求可能同时命中多条规则，网关按以下排序键**依次**比较，选出唯一目标：

1. `priority` 数值小者优先（显式优先级）；
2. 路径前缀更长者优先（更具体的规则优先）；
3. 必需请求头数量更多者优先（约束更强的规则优先）；
4. 路由 `id` 字典序（最终兜底，保证结果永远确定）。

已被配置**禁用（enabled=false）**或定义缺失的上游，其候选路由会被跳过；
若因此没有可路由目标，返回 `404 ROUTE_NOT_FOUND`，消息中附带每条候选规则的判定依据。

### 2.2 自相矛盾的规则在加载/热更新时即被拒绝

`ConfigValidator` 会拒绝无法确定唯一目标或语义非法的配置，例如：

- 两条规则 `priority` 相同，且路径前缀相容、方法集合相容、必需头不互斥
  → 同一请求可能同时命中且排序键并列（报 `优先级相同...无法确定唯一目标`）；
- 路由 id 重复 / 缺失、路径前缀非法、引用不存在的上游、`baseUrl` 非 http(s)、
  超时或重试次数为负等。

> 注：同前缀同优先级但**方法互斥**（GET vs POST）或对同一头名要求**不同具体值**
> （`X-Tenant: a` vs `X-Tenant: b`）的规则不会同时命中一个请求，因此允许共存。

### 2.3 请求体与逐跳头

- 请求体在入口被**完整缓冲**（`byte[]`），因此幂等请求的重试可以安全重放；
- 转发请求与回传响应都剔除逐跳头：`connection, keep-alive, proxy-authenticate,
  proxy-authorization, te, trailer, transfer-encoding, upgrade`，以及由网关注入的
  `host / content-length`，避免连接与分帧语义错乱。

---

## 3. 限流与配额语义

限流在路由匹配成功后、转发之前执行，判定顺序固定：

1. **调用方识别**：`X-Caller-Id`。仅当路由配置了 `allowedCallers` 白名单或调用方维度
   限流时才强制要求；缺失 → `401 CALLER_MISSING`。
2. **越权判定**：调用方不在白名单 → `403 CALLER_FORBIDDEN`。越权/缺身份请求在任何
   配额扣减**之前**被拒绝，绝不消耗他人配额。
3. **幂等去重**：`X-Request-Id`。同一 `路由 + 调用方 + X-Request-Id` 只计数一次；
   重复的重试/并发提交 → `409 REQUEST_DUPLICATED`，不重复扣桶。同键但请求内容指纹
   不同（方法/路径/体长度）也会被拒绝，避免歧义重放。
4. **调用方维度桶** `callerRatePerSecond`：每个「路由 × 调用方」独立令牌桶，
   一个调用方超额（`429 RATE_LIMITED_CALLER`）不影响其他调用方。
5. **路由维度桶** `globalRatePerSecond`：路由整体令牌桶，超额 →
   `429 RATE_LIMITED_GLOBAL`。

实现要点（`ratelimit` 包）：

- 令牌桶容量=每秒速率，按经过时间线性补令牌；**检查+扣减在同一把锁内完成**，
  调用方桶与路由桶要么都成功要么都不发生（路由桶拒绝时回滚已扣的调用方令牌），
  因此高并发下**绝不超发**；
- 并发测试（200 线程竞争容量 10 的桶）验证了每个令牌最多被一个请求拿走；
- 配置热更新成功后会**重建全部桶与去重表**，旧配额容量不会泄漏到新配置。

---

## 4. 上游异常、重试与结果归一化

### 4.1 重试语义（幂等 vs 非幂等）

- 仅幂等方法 `GET / HEAD / OPTIONS / PUT / DELETE` 允许重试，最多 `maxRetries+1` 次；
- 非幂等方法 `POST / PATCH ...` **最多尝试一次**，失败立即归一化返回，绝不错误重试；
- 触发重试的情况：连接失败、读超时、上游 `5xx`。上游 `4xx` 视为客户端错误，原样透传，不重试。

### 4.2 归一化错误码（对外可区分）

统一响应体：

```json
{ "error": { "code": "UPSTREAM_TIMEOUT", "message": "...", "httpStatus": 504 },
  "timestamp": "..." }
```

| code | HTTP | 含义 |
|---|---|---|
| `ROUTE_NOT_FOUND` | 404 | 无条件匹配，或命中路由的上游均已禁用 |
| `ROUTE_AMBIGUOUS` | 500 | 排序键完全并列（正常已被配置校验拦截） |
| `CONFIG_INVALID` | 500 | 热更新配置非法，旧配置保留 |
| `CALLER_MISSING` | 401 | 缺少 X-Caller-Id |
| `CALLER_FORBIDDEN` | 403 | 调用方不在白名单 |
| `RATE_LIMITED_GLOBAL` / `RATE_LIMITED_CALLER` | 429 | 路由/调用方维度超额 |
| `REQUEST_DUPLICATED` | 409 | 幂等键重复 |
| `UPSTREAM_CONNECT_FAILED` | 502 | 无法连接上游 |
| `UPSTREAM_TIMEOUT` | 504 | 单次调用超时 |
| `UPSTREAM_RETRIES_EXHAUSTED` | 504/502 | 幂等请求重试耗尽仍失败 |
| `UPSTREAM_ERROR` | 502 | 上游 5xx（非幂等只试一次时） |
| `UPSTREAM_BAD_RESPONSE` | 502 | 响应无法解析 / IO 异常 |
| `UPSTREAM_CIRCUIT_OPEN` | 503 | 上游熔断打开，快速失败 |
| `INTERNAL_ERROR` | 500 | 其他未预期错误 |

### 4.3 熔断

每个上游独立维护状态机：连续 5 次失败 → `OPEN` 10 秒（期间请求快速失败、**不再发往
已不可用目标**）→ 允许一次 `HALF_OPEN` 试探 → 成功关闭 / 失败重新打开。

---

## 5. 配置热更新的一致性

配置以**不可变快照**（`GatewayConfig` record）形式保存于 `AtomicReference`：

- 热更新流程固定为「解析 JSON → 全量校验 → 原子替换引用」，任一步失败都**保留旧快照**，
  因此永远不会出现部分规则生效的半生效状态；
- 进行中的请求在进入流水线时拿到当前快照引用并全程使用它，可以执行完；
  新请求只会看到完整的新配置。把上游改为 `enabled=false` 后，新请求不会再被路由到该目标；
- 更新方式：
  - `GET  /__admin/config` 查看当前快照；
  - `POST /__admin/config/reload`（body 为完整 JSON）原子切换；
  - `gateway.config.path` 指向外部文件时，后台守护线程每秒轮询文件 mtime，
    检测到变更自动做同样的「校验+原子切换」，坏配置被跳过并继续沿用旧配置。

示例：

```bash
curl -s -X POST http://127.0.0.1:18080/__admin/config/reload \
  -H 'Content-Type: application/json' --data @new-routes.json
```

配置 JSON 结构见 `src/main/resources/gateway-routes-demo.json`。

---

## 6. 验证方法（本机独立复现）

### 6.1 自动化测试

```bash
mvn test
```

共 39 个用例，全部离线可复现：

| 测试类 | 覆盖内容 |
|---|---|
| `RouteMatcherTest` (6) | 优先级、最长前缀、id 兜底、方法/头过滤、上游禁用 |
| `ConfigValidatorTest` (8) | 同优先级冲突、方法/头互斥、幽灵上游、重复 id、非法 URL |
| `RateLimiterConcurrencyTest` (5) | 200 并发不超发、幂等键并发只计数一次、调用方隔离、非法请求不占配额、双维度限流 |
| `UpstreamDispatcherTest` (8) | JDK `HttpServer` 模拟本机上游：透传/逐跳头剔除、幂等重试、非幂等不重试、超时、连接失败、熔断、前缀重写、请求体重放 |
| `GatewayEndToEndTest` (11) | 真实 HTTP 全链路：转发、404/401/403/409/429、重试、502/504 归一化、禁用上游、坏配置拒绝、并发热更新一致性 |

每个用例都通过 SLF4J 打印**输入、判定依据与归一化结果**，例如：

```
[ratelimit] 放行 route=v2-orders-explicit, caller=alpha, requestId=.., callerTokens=1.00, globalTokens=99.00
[route] 判定=HIT route=v2-orders-explicit, 依据=[priority=10, pathPrefix=/api/v2/orders(长度14), ...]
[gateway] 归一化失败结果: code=UPSTREAM_TIMEOUT, httpStatus=504, attempts=3
```

### 6.2 手工演示（demo profile）

```bash
java -jar target/edge-api-gateway-java-1.0-SNAPSHOT.jar --spring.profiles.active=demo

# 幂等重试：前两次 503、第三次 200（v2 路由 maxRetries=2）
curl -s http://127.0.0.1:18080/api/v2/flaky -H 'X-Caller-Id: alpha'
# 非幂等不重试 -> 502 UPSTREAM_ERROR（只尝试 1 次）
curl -s -X POST http://127.0.0.1:18080/api/v2/error -H 'X-Caller-Id: alpha'
# 超时重试耗尽 -> 504（约 9 秒，3 次 x 3s）
curl -s http://127.0.0.1:18080/api/v2/slow -H 'X-Caller-Id: alpha'
# 调用方限流：callerRate=2/s，连发观察 429，换 caller 不受影响
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://127.0.0.1:18080/api/v2/orders/1 -H 'X-Channel: partner' -H 'X-Caller-Id: alpha'; done
```

内置 demo 上游端点：`/__upstream/ok`（回显）、`/slow`（睡 5s）、`/flaky`（前两次 503）、
`/error`（恒 500）。

---

## 7. 代码结构

```
config      规则/上游不可变模型、JSON 加载、自洽校验、快照与热更新（文件轮询+管理接口）
routing     请求特征、确定性路由匹配（排序键+判定依据）
ratelimit   线程安全令牌桶、两级限流、幂等去重
upstream    JDK HttpClient 转发、超时/重试/熔断、异常归一化、逐跳头处理
proxy       统一入口流水线、管理接口、统一错误响应
demo        profile=demo 时启用的内置本机上游
```
