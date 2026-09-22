# Edge API Gateway（本地可运行边缘网关）

一个零外部依赖、可在本机独立运行的 Spring Boot 边缘网关：按可配置规则把请求转发到
**本机上游服务**，在所有入口（HTTP 代理、重试、并发、热更新）下提供一致、可解释的
路由、鉴权、限流与异常归一化结果。

- Java 21 / Spring Boot 3.4，上游转发使用 JDK 内置 `HttpClient`
- 测试用假上游使用 JDK 内置 `HttpServer`，随机本地端口
- 上游主机强制为 loopback（`127.0.0.1` / `localhost` / `[::1]`），不访问外部网络
- 无需任何真实凭据

---

## 1. 快速开始

```bash
# 运行全部测试（49 个用例，含并发与全链路，约 30 秒）
mvn test

# 以 demo 模式启动：网关 :8080 + 两个本机假上游 :18081(稳定) / :18082(故障)
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

demo 启动后可直接验收：

```bash
# 正常转发
curl -H "X-Caller-Id: shop-a" -H "X-Api-Version: v2" \
     -X POST http://127.0.0.1:8080/api/v2/orders/echo/sub -d 'hello'

# 幂等重试（上游前两次 503，第三次 200）
curl http://127.0.0.1:8080/flaky

# 非幂等超时（不重试，504）
curl -X POST http://127.0.0.1:8080/slow -d x

# 上游连接失败（502 归一化信封）
curl http://127.0.0.1:8080/boom

# 限流（2 次/秒，第 3 次 429）
for i in 1 2 3; do curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-Caller-Id: shop-a" http://127.0.0.1:8080/api/orders/echo; done

# 管理端
curl http://127.0.0.1:8080/__gateway/admin/config
curl -X POST http://127.0.0.1:8080/__gateway/admin/reload
```

使用外部文件配置（支持热更新）：

```bash
cp src/main/resources/gateway-config-demo.json /tmp/gw.json
mvn spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--edge.gateway.config-location=file:/tmp/gw.json"
# 修改 /tmp/gw.json 后：curl -X POST http://127.0.0.1:8080/__gateway/admin/reload
# 也可开启轮询自动热更：--edge.gateway.watch-enabled=true
```

---

## 2. 配置文件格式

JSON（`edge.gateway.config-location`，支持 `classpath:` 与 `file:`）：

```json
{
  "callerHeader": "X-Caller-Id",
  "defaultTimeoutMillis": 3000,
  "maxBodyBytes": 1048576,
  "dedupTtlMillis": 60000,
  "routes": [
    {
      "id": "orders-v2",
      "method": "ANY",
      "pathPrefix": "/api/v2/orders",
      "requiredHeaders": { "x-api-version": "v2", "x-env": "regex:dev-\\d+" },
      "upstreamBaseUrl": "http://127.0.0.1:18081",
      "stripPrefix": "/api/v2/orders",
      "timeoutMillis": 3000,
      "retries": 2,
      "requireCaller": true,
      "allowedCallers": ["shop-a", "shop-b"],
      "rateLimit": { "permitsPerWindow": 3, "windowMillis": 1000, "dailyQuota": 100 }
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `method` | `GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS/ANY`（ANY 匹配任意方法） |
| `pathPrefix` | 路径前缀匹配，必须以 `/` 开头 |
| `requiredHeaders` | 必需头，值为字面量相等，或以 `regex:` 前缀写正则（对完整值 `matches`） |
| `upstreamBaseUrl` | 仅允许 loopback 主机；与裁剪后的请求路径拼接 |
| `stripPrefix` | 转发前裁剪的前缀；留空/不填表示保留完整路径 |
| `timeoutMillis` | 单次上游调用读超时；0 表示取全局默认 |
| `retries` | **额外**重试次数（仅幂等方法生效） |
| `requireCaller` / `allowedCallers` | 是否强制调用方身份；非空白名单表示仅这些调用方可访问 |
| `rateLimit.permitsPerWindow` | 每窗口许可证数；-1 关闭窗口限流 |
| `rateLimit.dailyQuota` | UTC 日配额；-1 关闭 |

---

## 3. 路由优先级（确定性）

请求按顺序判定，失败原因三级可区分：

1. **没有任何**路由的 `pathPrefix` 命中 → `ROUTE_NOT_FOUND`（404）
2. 路径命中但**方法**都不满足（显式方法路由，ANY 除外）→ `METHOD_NOT_ALLOWED`（405）
3. 路径+方法命中但**必需头**不满足 → `HEADER_CONDITION_FAILED`（428）

多个规则同时命中时，按固定打分选出**唯一**目标，与配置书写顺序无关：

```
score = 100 × 命中的必需头数量        // 条件越多越优先，一个头条件压过任意前缀长度差
      + pathPrefix 字符长度            // 前缀越长越优先
      + (显式方法 ? 1 : 0)            // 显式方法优先于 ANY
```

完全同分时按路由 `id` 字典序决出胜者。因此"带额外头部条件的规则"永远优先于
"只有短前缀的规则"，"更长前缀"优先于"更短前缀"，结果稳定且可解释。

**矛盾规则在配置发布前即被拒绝**：两条路由的匹配签名（方法 + 路径前缀 + 必需头
名称集合）完全相同时无法确定优先级，校验直接报 `CONFIG_INVALID`，配置不会生效。

---

## 4. 鉴权与调用方语义

- `requireCaller=true` 时，缺失 `callerHeader`（默认 `X-Caller-Id`）→
  `MISSING_CALLER`（401）。
- 白名单非空且调用方不在其中 → `CALLER_FORBIDDEN`（403）。
- `requireCaller=false` 却配置了白名单属于语义矛盾，配置校验阶段拒绝。
- 401 与 403 归一化为不同错误码，调用方可区分"未表明身份"与"越权"。

---

## 5. 限流语义

维度为 **路由 × 调用方**（未携带身份时按 `ANON`，但开放路由通常不配置限流）。

- **固定窗口**：`AtomicInteger#incrementAndGet` 计数，许可证为 N 时任意并发强度下
  最多放行 N 个请求，**绝不超发**；窗口到期后计数器整体替换。
- **日配额**：UTC 天独立的 `AtomicLong` 计数。判定顺序为先配额后窗口，
  配额耗尽不累加窗口计数；两类超限归一化为不同错误码：
  `RATE_LIMITED`（429）/ `QUOTA_EXCEEDED`（429）。
- **调用方隔离**：每个调用方独立 key，A 超额不影响 B。
- **同一请求不重复计数**（重试与并发安全）：
  - 请求通过 `X-Request-Id`（或 `Idempotency-Key`）标识；同一 id 首次判定时
    先在去重表上 **`putIfAbsent` 占位**（in-progress 哨兵），只有占位者执行计数；
  - 同号的并发/重试请求等待占位者结论并**复用**，全部拿到与首请求相同的判定，
    但窗口/配额只消耗 1 个名额；
  - 同号请求落在不同 `路由 × 调用方` 指纹上 → `IDEMPOTENCY_CONFLICT`（409）；
  - 判定异常会释放占位；去重结论 TTL 由 `dedupTtlMillis` 控制。
  - 未携带请求 id 的请求不参与去重（网关注入的内部 id 每次不同）。

---

## 6. 上游转发与重试

请求体在入口一次性有界读入（超过 `maxBodyBytes` → `PAYLOAD_TOO_LARGE` 413，
不进入转发），幂等重试时重发同一份字节。

**幂等 vs 非幂等的重试语义严格不同：**

| 方法类别 | 方法 | 连接失败 / 读超时 | 上游 502/503 | 上游 4xx |
| --- | --- | --- | --- | --- |
| 幂等 | GET HEAD OPTIONS PUT DELETE | 最多重试 `retries+1` 次（线性退避） | 重试 | 不重试，透传 |
| 非幂等 | POST PATCH 等 | **只发一次**，分别归一化为 502/504 | 不重试，透传响应 | 不重试，透传 |

> 非幂等请求超时后无法确认上游是否已处理，因此绝不自动重试，避免重复副作用；
> 调用方可携带 `Idempotency-Key` 自行安全重试（限流层会保证不重复计数）。

**逐跳头处理（RFC 7230 §6.1）**：请求与响应两个方向都剥离
`Connection/Keep-Alive/Proxy-Authenticate/Proxy-Authorization/TE/Trailer/
Transfer-Encoding/Upgrade`，并额外剥离客户端在 `Connection` 头中声明的扩展令牌、
网关本地消费的 `X-Forwarded-*` 等；`Host/Content-Length` 由 HTTP 客户端按目标
重新生成。网关不参与协议升级（强制 HTTP/1.1 上行）。

---

## 7. 异常归一化规则

传输级失败转换为统一 JSON 错误信封，不泄漏堆栈/连接细节：

```json
{
  "errorCode": "UPSTREAM_TIMEOUT",
  "status": 504,
  "message": "upstream did not respond within 300ms",
  "requestId": "9803a1a5...",
  "routeId": "slow-post",
  "callerId": null
}
```

| errorCode | HTTP | 触发条件 |
| --- | --- | --- |
| `ROUTE_NOT_FOUND` | 404 | 无路径前缀匹配 |
| `METHOD_NOT_ALLOWED` | 405 | 路径匹配但方法不允许 |
| `HEADER_CONDITION_FAILED` | 428 | 必需头（字面量/正则）不满足 |
| `MISSING_CALLER` | 401 | 强制鉴权路由缺失调用方头 |
| `CALLER_FORBIDDEN` | 403 | 调用方不在白名单 |
| `RATE_LIMITED` | 429 | 窗口许可证耗尽 |
| `QUOTA_EXCEEDED` | 429 | UTC 日配额耗尽 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同号请求的路由/调用方指纹冲突 |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体超过大小上限 |
| `UPSTREAM_CONNECT_FAILED` | 502 | 无法连接本机上游（非幂等只判定一次） |
| `UPSTREAM_TIMEOUT` | 504 | 上游读超时（非幂等不重试） |
| `UPSTREAM_UNAVAILABLE` | 502 | 其它 IO 故障 / 重试耗尽 |
| `UPSTREAM_BAD_GATEWAY` | 502 | 上游返回无法解析的响应 |
| `CONFIG_INVALID` | 500 | 配置缺失/解析失败/语义矛盾/非 loopback 上游 |
| `INTERNAL_ERROR` | 500 | 兜底，不泄漏内部细节 |

上游的 4xx/5xx 是**上游业务结果**，按原状态码与响应体透传（附带
`X-Gateway: edge-api-gateway`、`X-Gateway-Route`、`X-Request-Id` 头），
与网关自身错误信封天然可区分。

---

## 8. 配置热更新一致性

- 新配置必须**先完整加载 + 全部通过语义校验**，再通过对 volatile 引用的单次写
  **原子发布**，版本号单调递增。
- 任何一步失败都整体拒绝：旧配置原样继续服务、版本不变——**不存在半生效状态**
  （见 `ConfigStore.reload()`）。
- 每个请求在入口处一次性**快照当前配置**，整条链路（路由、鉴权、限流参数、
  上游目标）都基于同一份快照；配置切换不会让进行中的请求中途改路由到已下线目标。
- 生效方式：`POST /__gateway/admin/reload` 手动触发；或将
  `edge.gateway.watch-enabled=true` 打开文件轮询（仅 `file:` 位置）。
- 启动时首份配置非法会 fail-fast 阻止应用对外服务。

---

## 9. 验证方法

```bash
mvn test
```

测试全部使用本机 JDK HttpServer 假上游与随机端口，不依赖外部网络或凭据。
关键覆盖（每个用例日志均打印 **输入 → 判定依据 → 归一化结果**）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `RouterTest` | 前缀/头部/方法优先级、同分 id 决胜、404/405/428 三级原因、正则头 |
| `ConfigValidatorTest` | 矛盾签名、重复 id、非 loopback 上游、坏正则、非法限流/鉴权参数 |
| `RateLimitServiceTest` | 50 并发抢 5 证不超发、调用方隔离、日配额、同号重试与并发不重复计数、409 指纹冲突 |
| `UpstreamForwarderTest` | 幂等重试成功、POST 不重试且只命中一次、超时/连接失败/5xx 归一化、4xx 不重试、逐跳头剥离、请求体透传 |
| `GatewayEndToEndTest` | 真实网关全链路：401/403/404/405/413/429/502/504 错误信封、端到端去重、重试、超时、透传 |
| `HotReloadTest` | 非法配置拒绝且旧配置继续服务、合法配置原子切换、100 并发请求 × 5 次热更新无 5xx/半成品状态 |
| `AdminControllerTest` | 配置摘要与手动 reload 接口 |

手工验收见第 1 节 demo 命令（含限流、幂等重试、非幂等超时、连接失败、
文件热更新与矛盾配置拒绝的完整复现路径）。

---

## 10. 模块结构

```
common/        常量、逐跳头集合
error/         归一化错误码、统一异常、@RestControllerAdvice、错误响应模型
model/         GatewayConfig / RouteConfig / RateLimitConfig（JSON 反序列化 POJO）
config/        配置加载、语义校验、原子发布 ConfigStore、文件轮询 ConfigWatcher
routing/       请求特征、确定性 Router、入口 RouteResolver（路由+鉴权+快照）
ratelimit/     固定窗口/日配额/请求去重（CAS，并发安全）
upstream/      UpstreamForwarder（JDK HttpClient、重试、逐跳头、异常归一化）
proxy/         GatewayProxyController（统一入口判定链）
admin/         /__gateway/admin 配置查询与 reload
demo/          demo profile 的本机假上游
```
