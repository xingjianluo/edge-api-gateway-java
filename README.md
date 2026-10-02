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

# 运行全部测试（65 个用例，完全离线）
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
- `priority`、`timeoutMillis`、`maxRetries`、两级限流、`allowedCallers`、`quotaId`

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

1. **调用方识别**：`X-Caller-Id`。仅当路由配置了 `allowedCallers` 白名单、调用方维度
   按秒限流，或路由归属了周期配额时才强制要求；缺失 → `401 CALLER_MISSING`。
2. **越权判定**：调用方不在白名单 → `403 CALLER_FORBIDDEN`。越权/缺身份请求在任何
   配额扣减**之前**被拒绝，绝不消耗他人配额。
3. **幂等去重**：`X-Request-Id`。同一 `路由 + 调用方 + X-Request-Id` 只计数一次；
   重复的重试/并发提交 → `409 REQUEST_DUPLICATED`，不重复扣按秒桶，也**不重复扣周期配额**。
   同键但请求内容指纹不同（方法/路径/体长度）也会被拒绝，避免歧义重放。
4. **调用方维度桶** `callerRatePerSecond`：每个「路由 × 调用方」独立令牌桶，
   一个调用方超额（`429 RATE_LIMITED_CALLER`）不影响其他调用方。
5. **路由维度桶** `globalRatePerSecond`：路由整体令牌桶，超额 →
   `429 RATE_LIMITED_GLOBAL`。
6. **周期共享配额** `quotaId`：调用方在同一配额范围覆盖的多条路由上共用一份周期总额度，
   用尽 → `429 QUOTA_EXHAUSTED`（独立 code，与按秒限流可区分）。被配额拒绝的请求
   **绝不会发到上游**。

### 3.1 按秒令牌桶（既有能力）

实现要点（`ratelimit` 包）：

- 令牌桶容量=每秒速率，按经过时间线性补令牌；**检查+扣减在同一把锁内完成**，
  调用方桶与路由桶要么都成功要么都不发生（路由桶/周期配额拒绝时回滚已扣的调用方令牌），
  因此高并发下**绝不超发**；
- 并发测试（200 线程竞争容量 10 的桶）验证了每个令牌最多被一个请求拿走。

### 3.2 周期共享配额（`quota` 包）

业务上常需要「某调用方在一小时/一天/一周内，横跨多条接口合计最多能调用多少」的预算控制。
它与按秒限流是**两层互相独立、串联执行**的控制：

- **作用范围（共享）**：顶层 `quotas` 定义若干配额策略，路由通过 `quotaId` 归属。
  多条路由引用同一个 `quotaId` 时，同一调用方（`X-Caller-Id`）在这些路由上的调用
  **从同一份额度累计扣减**；不同调用方各有独立计数器，互不影响；
  不引用任何配额的路由、不携带身份的匿名路由完全不受这层约束。
- **窗口与重置**：`resetPeriod` 取 `HOUR` / `DAY` / `WEEK`，`timeZone` 为 IANA 时区
  （如 `Asia/Shanghai`，缺省 `UTC`）。窗口起点按时区归一化：整点 / 当天 00:00 /
  **周一** 00:00（ISO-8601）。窗口翻转时旧计数自然失效，新窗口额度完整恢复，
  **绝不跨窗口重复计数**。
- **并发与重试语义**：
  - 同一窗口内、跨多条路由上的并发请求合计放行数绝不超过 `limit`
    （检查余量与扣 1 在同一把窗口锁内原子完成；不足时零扣减，不会"拒绝了却占额度"）；
  - 同一次客户端请求无论上游内部重试几次，配额只在入口检查时扣一次
    （重试发生在上游分发阶段，不会再次进入配额检查）；
  - 重复提交、越权、缺少有效身份的请求在配额扣减之前就被拒绝，**不消耗额度**；
  - 周期配额与两级按秒桶的检查同处一个原子区间：任一步失败都会回滚此前所有扣减，
    不会出现只扣一半或半生效状态。
- **拒绝结果**：`429 QUOTA_EXHAUSTED`，消息带配额 id 与上限；与
  `RATE_LIMITED_CALLER` / `RATE_LIMITED_GLOBAL` 明确可区分。

### 3.2.1 配额什么时候开始生效：启动阶段 vs 热更新阶段

共享配额在**服务启动完成、开始接收第一个请求之前**就必须已经建账，两个阶段的行为如下：

| 阶段 | 触发时机 | 配额行为 |
|---|---|---|
| **启动阶段（初始配置）** | Spring 容器初始化时，`ConfigService` 先加载 `gateway.config.path`
  （外部文件或 classpath）指向的初始配置；随后 `QuotaManager` 在自己的初始化回调中
  **对初始快照执行一次首次对账**（`reconcile`），为配置中的每个 `quotaId` 建立空计数表 |
  初始配置里已有的周期配额在**第一个请求到来时立即生效**，无需任何额外操作或热更新；
  首次对账与运行期热更新走的是**同一条幂等且并发安全的对账代码路径**，不存在两套初始化逻辑 |
| **热更新阶段（运行中）** | `POST /__admin/config/reload` 或外部配置文件 mtime 变化，
  快照「解析 → 校验 → 原子替换」成功后通知各组件，`QuotaManager` 按策略身份对账 |
  见 3.3：无关变更保留已用量，额度/范围调整整体生效，周期/时区变化重新计账 |

> 实现说明：初始加载**不**走"快照切换通知"，因为 `ConfigService` 的初始加载发生在它自己的
> 初始化回调中，此刻其他监听器可能尚未注册；`QuotaManager` 因此在自身启动回调里主动读取
> 当前快照做首次对账。对账由 `ConcurrentHashMap.putIfAbsent` / `retainAll` 等原子操作构成，
> 即使与首批请求或紧接的热更新并发，也不会漏计、重复计数或出现"已拒绝却占用额度"。

配置示例：

```json
{
  "quotas": [
    { "id": "hourly-budget", "limit": 10, "resetPeriod": "HOUR", "timeZone": "Asia/Shanghai" }
  ],
  "routes": [
    { "id": "v1-api", "pathPrefix": "/api/v1", "upstream": "demo", "quotaId": "hourly-budget", "...": "..." },
    { "id": "v2-api", "pathPrefix": "/api/v2", "upstream": "demo", "quotaId": "hourly-budget", "...": "..." }
  ]
}
```

### 3.3 周期配额与配置热更新的兼容范围

配置以不可变快照原子切换；周期配额状态由 `QuotaManager` 按**策略身份（quotaId）+重置规则**
对账，而不是像按秒桶那样在每次切换时无条件清空：

| 热更新内容 | 当前周期已用额度 | 行为 |
|---|---|---|
| 只改超时、重试、上游启停、按秒速率、增删不相关路由等 | **保留不清零** | 已用量延续，到窗口边界才重置 |
| 调整配额 `limit`（改大/改小），周期与时区不变 | 保留已用量，套新上限 | 改小后已用 ≥ 新上限的调用方立即被拒，**不能因改配反而多用**；改大可在剩余窗口内补到新上限 |
| 调整配额覆盖的路由集合（quotaId 不变） | 保留已用量 | 计数器只按 `配额×调用方` 记账，与具体路由无关 |
| 修改 `resetPeriod` 或 `timeZone` | 旧窗口整体作废 | 按新重置规则开新窗口重新计账，杜绝新旧额度并存与边界重复计数 |
| 删除配额策略 / 删除调用方所在路由对配额的引用 | 该策略状态整表丢弃 | 旧额度不会泄漏；在途请求若持旧快照，本次不做配额扣减也不重建已删状态 |
| 新配额配置非法（幽灵引用、limit≤0、坏时区、未知周期） | 不受影响 | 校验失败，整份快照被拒，继续沿用旧配置 |

过期窗口由后台守护线程每 30 秒扫描回收（删除策略状态、清退历史调用方的旧窗口），
长时间运行、调用方众多时不会无限占用内存；请求热路径只做一次 O(1) 哈希定位，
历史调用方不会拖慢新请求。

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
| `RATE_LIMITED_GLOBAL` / `RATE_LIMITED_CALLER` | 429 | 路由/调用方维度按秒超额 |
| `QUOTA_EXHAUSTED` | 429 | 超出调用方在该配额范围内的周期总额度（跨多路由共享） |
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

配置 JSON 结构见 `src/main/resources/gateway-routes-demo.json`。其中配额为可选顶层段：

```json
"quotas": [
  { "id": "hourly-budget", "limit": 10, "resetPeriod": "HOUR", "timeZone": "Asia/Shanghai" }
]
```

- `limit`：每个调用方每个周期的总次数（必须为正）；
- `resetPeriod`：`HOUR` / `DAY` / `WEEK`（缺省 `DAY`）；
- `timeZone`：重置边界时区，IANA 名称（缺省 `UTC`）；
- 路由用 `"quotaId": "hourly-budget"` 归属；引用不存在的配额、limit≤0、坏时区、
  未知周期都会在加载/热更新时被校验拒绝（旧配置继续生效）。

---

## 6. 验证方法（本机独立复现）

### 6.1 自动化测试

```bash
mvn test
```

共 65 个用例，全部离线可复现：

| 测试类 | 覆盖内容 |
|---|---|
| `RouteMatcherTest` (6) | 优先级、最长前缀、id 兜底、方法/头过滤、上游禁用 |
| `ConfigValidatorTest` (11) | 同优先级冲突、方法/头互斥、幽灵上游、重复 id、非法 URL、配额幽灵引用/非正额度/非法时区 |
| `RateLimiterConcurrencyTest` (9) | 200 并发不超发、幂等键并发只计数一次、调用方隔离、非法请求不占配额、双维度限流；**跨路由共享周期配额、配额拒绝回滚按秒令牌、幂等不重复占配额、缺身份/越权不耗周期额度** |
| `QuotaManagerTest` (11) | **跨路由共享、调用方隔离、小时窗口边界翻转、200 并发不超总额、时区归一化（日/周）、无关热更新不清零、配额改小不能多用/改大补到新上限、周期时区变更重新计账、策略删除回收、过期窗口回收不拖慢新请求、并发热更新对账不混用新旧额度** |
| `UpstreamDispatcherTest` (8) | JDK `HttpServer` 模拟本机上游：透传/逐跳头剔除、幂等重试、非幂等不重试、超时、连接失败、熔断、前缀重写、请求体重放 |
| `GatewayEndToEndTest` (14) | 真实 HTTP 全链路：转发、404/401/403/409/429、重试、502/504 归一化、禁用上游、坏配置拒绝、并发热更新一致性；**跨路由共享配额 429 可区分、配额拒绝不转发上游、无关热更新不清零与策略调整整体生效** |
| `StartupQuotaEndToEndTest` (5) | **全程不做任何热更新**、只用初始配置启动：共享配额启动即生效且超额 429 不转上游、调用方隔离、跨 v1/v2 共发 30 并发合计只放行 3、上游重试 3 次只占 1 次额度、缺身份/越权连发不占用他人额度 |

每个用例都通过 SLF4J 打印**输入、判定依据与归一化结果**，例如：

```
[ratelimit] 放行 route=v2-orders-explicit, caller=alpha, requestId=.., callerTokens=1.00, globalTokens=99.00, quota=hourly-budget
[quota] 放行 quotaId=hourly-budget, caller=alpha, 窗口起点=..., 已用=3, 上限=10, 剩余=7
[quota] 拒绝: QUOTA_EXHAUSTED quotaId=hourly-budget, caller=alpha, 窗口起点=..., 已用=10, 上限=10
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

# 周期共享配额：demo 中 v1-api 与 v2-api 共享 alpha 每小时 10 次（Asia/Shanghai 整点重置）
# 在两条路由上交替调用，第 11 次起返回 429/QUOTA_EXHAUSTED；换调用方或等待整点后恢复
for i in $(seq 1 11); do curl -s -o /dev/null -w "v1 #$i -> %{http_code}\n" \
  http://127.0.0.1:18080/api/v1/ping -H 'X-Caller-Id: alpha'; \
  curl -s -o /dev/null -w "v2 #$i -> %{http_code}\n" \
  http://127.0.0.1:18080/api/v2/ping -H 'X-Caller-Id: alpha'; done | tail -4
```

#### 启动即生效：不做任何热更新直接复现超额拒绝

上面的 demo 配置自带每小时 10 次的共享配额；服务**只用初始配置启动、启动后不调用任何
`/__admin/config/reload`、不改配置文件**，配额就应当立即生效。用小额度配置可以在几条命令内
稳定看到超额拒绝：

```bash
# 1) 准备一份小额度的初始配置（两条路由共享、每调用方每天 3 次）
cat > /tmp/gateway-startup.json <<'JSON'
{
  "upstreams": [
    { "id": "demo", "baseUrl": "http://127.0.0.1:18080", "enabled": true }
  ],
  "quotas": [
    { "id": "startup-budget", "limit": 3, "resetPeriod": "DAY", "timeZone": "UTC" }
  ],
  "routes": [
    { "id": "v1-api", "pathPrefix": "/api/v1", "upstream": "demo",
      "prefixRewrite": "/__upstream", "priority": 20, "timeoutMillis": 3000,
      "maxRetries": 0, "globalRatePerSecond": 1000, "callerRatePerSecond": 1000,
      "allowedCallers": ["alpha"], "quotaId": "startup-budget" },
    { "id": "v2-api", "pathPrefix": "/api/v2", "upstream": "demo",
      "prefixRewrite": "/__upstream", "priority": 30, "timeoutMillis": 3000,
      "maxRetries": 0, "globalRatePerSecond": 1000, "callerRatePerSecond": 1000,
      "allowedCallers": ["alpha"], "quotaId": "startup-budget" }
  ]
}
JSON

# 2) 按这份初始配置启动（不要做任何热更新）
java -jar target/edge-api-gateway-java-1.0-SNAPSHOT.jar \
  --spring.profiles.active=demo \
  --gateway.config.path=/tmp/gateway-startup.json

# 3) 另开一个终端：同一调用方在两条共用额度的路由上连续请求（启动后直接执行）
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "v1 #$i -> %{http_code}\n" \
    http://127.0.0.1:18080/api/v1/ping -H 'X-Caller-Id: alpha'
  curl -s -o /dev/null -w "v2 #$i -> %{http_code}\n" \
    http://127.0.0.1:18080/api/v2/ping -H 'X-Caller-Id: alpha'
done
```

预期：前 3 次（无论打 v1 还是 v2）为 `200`，从第 4 次起稳定返回 `429`，响应体含
`"code":"QUOTA_EXHAUSTED"` 且**不含**上游回显字段（请求未到达上游）。修复前的现象是：
启动后不触发热更新时，这里会一直 `200`、配额形同虚设，直到手动提交一次同样的配置后才开始拦截。

配套的自动化复现：`StartupQuotaEndToEndTest` 在真实 HTTP 服务里只加载一次初始配置
（用临时文件 + 随机端口），全过程不调用 `replaceFromJson`，覆盖启动即超额拒绝、跨路由共享、
调用方隔离、并发不超发、上游重试不多占额度、缺身份/越权不多占额度。

内置 demo 上游端点：`/__upstream/ok`（回显）、`/slow`（睡 5s）、`/flaky`（前两次 503）、
`/error`（恒 500）。

---

## 7. 代码结构

```
config      规则/上游/周期配额不可变模型、JSON 加载、自洽校验、快照与热更新（文件轮询+管理接口）
routing     请求特征、确定性路由匹配（排序键+判定依据）
ratelimit   线程安全令牌桶、两级按秒限流、幂等去重、周期配额原子接入
quota       按调用方跨路由共享的周期配额：时区归一化窗口、原子检查扣减、热更新对账、过期回收
upstream    JDK HttpClient 转发、超时/重试/熔断、异常归一化、逐跳头处理
proxy       统一入口流水线、管理接口、统一错误响应
demo        profile=demo 时启用的内置本机上游
```
