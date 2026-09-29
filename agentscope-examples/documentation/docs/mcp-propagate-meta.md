# MCP 元数据传播（propagateMeta）

> 适用范围：`agentscope-core` 的 MCP 工具调用链路（`McpClientWrapper` / `McpToolManager` / `McpTool`）。
> 状态标注：**三级静态控制 = 已实现并经实机验证**；**运行时动态控制（请求级）= 设计提案，尚未实现**（见 §8）。

---

## 1. 概述：一个"元数据出口"管控机制

Agent 与 MCP Server 之间每一次 `tools/call`，除了工具名和参数，还可能携带一个 `_meta` 字段。它承载两类信息：

1. **应用元数据**：应用在 `RuntimeContext` 中放入的 `McpMeta` 条目（如租户 ID、会话 ID、链路追踪标记）；
2. **框架元数据**：框架自动注入的 `io.agentscope/toolCallId`，用于跨进程关联同一次工具调用。

`_meta` 一旦发出，就**离开了本进程的信任边界**。企业内部元数据（租户标识、用户标识）会被持久化、聚合、甚至被第三方 MCP Server 转售或泄露。因此 AgentScope-Java 把"meta 是否发送"建模为一个**独立于功能本身的出口管控（egress control）问题**，提供从部署期到运行期的分层开关：

| 层级 | 粒度 | 生效方式 | 状态 |
|------|------|---------|------|
| 连接级 | 一条 MCP 连接（所有工具） | 部署期配置，运行期实时热切换 | ✅ 已实现 |
| 注册级 | 一次 `registerTool` 注册 | 部署期（作为工具级默认值） | ✅ 已实现 |
| 工具级 | 单个工具覆盖注册值 | 部署期 | ✅ 已实现 |
| 请求级 | 单次工具调用 | 运行期由应用逐调用决定 | 🔧 设计提案 |

设计原则一句话：**默认可用（fail-open at default），一旦关闭严格失效（fail-closed at deny），高层级是低层级永远无法突破的天花板。**

---

## 2. 核心概念：什么会被发出去

一次传播开启的工具调用，`_meta` 字段的完整内容为：

```text
_meta = {
  ... RuntimeContext.get(McpMeta.class) 中的全部条目,   // 应用自己放的
  "io.agentscope/toolCallId": <本次工具调用的 ID>        // 框架自动注入，应用无法通过 meta 内容控制
}
```

- 内容来源是**每次调用时**从 `RuntimeContext` 实时读取（`McpTool.buildMetaMap` → `extractMcpMeta`），所以应用可以逐调用注入不同的元数据。
- `toolCallId` 由框架在传播开启时自动附加——它是框架关联标识，不算用户数据，但它同样会离开进程。因此**关闭传播 = 两者一并拦截**（见 §4.4 的耦合讨论）。
- 传播关闭时，`_meta` 字段**整体省略**（而不是发送空对象）——对端从协议层面就看不到任何元数据。

---

## 3. 三级静态控制（已实现）

### 3.1 三个层级

| 层级 | API | 语义 |
|------|-----|------|
| **连接级** | `clientWrapper.setPropagateMeta(boolean)` | 总闸与天花板。`volatile` 存储，实时生效，对已注册工具立即起作用 |
| **注册级** | `McpToolManager.registerTool(..., propagateMeta)` | 注册时为该工具提供默认值；仅当工具级未显式覆盖时生效 |
| **工具级** | `McpTool.isPropagateMeta()`（经由注册路径设置） | 单工具覆盖注册级默认值 |

### 3.2 解析与生效

配置解析（`McpClientManager`）采用 `工具级 ?? 注册级 ?? 默认true` 的三段式；实际生效在 `McpTool.callAsync` 中：

```java
// McpTool.callAsync（简化）
boolean propagate = propagateMeta                 // 工具级（已吸收注册级默认）
                 && clientWrapper.isPropagateMeta(); // 连接级，volatile 实时读
Map<String, Object> metaMap = propagate ? buildMetaMap(param) : null;  // null → _meta 省略
```

**生效公式：`发送 meta ⟺ 连接级 && 工具级`**（注册级只是工具级的默认来源）。

### 3.3 热切换

连接级开关是 `volatile` 布尔值且在**每次工具调用时实时读取**：运行中把连接关掉，已注册工具的下一次调用立即不发 meta，无需重连或重注册。这使其成为运维层面的"紧急熔断开关"。

### 3.4 实测语义（已在真实 MCP Server 上验证）

| 场景 | 配置 | 实测结果 |
|------|------|---------|
| 工具级覆盖 | 连接 ON + 工具 OFF | 该工具不发送，同连接其他工具正常发送 |
| 连接级天花板 | 连接 OFF + 工具 ON | 该工具同样不发送（AND，工具级无法突破） |
| 防呆 | 连接 OFF 时调 `setToolPropagateMeta(true)` | 直接抛 `IllegalArgumentException`，fail-loud |

---

## 4. 运行时动态控制（请求级，设计提案）

### 4.1 动机：把决定权交给"这一次调用"

三级静态开关都是**部署期**的决定。但很多合规与隐私决策只有**运行期**、甚至**单次调用**的上下文才能做出：

- 认证中间件解析请求后才知道该租户/用户是否允许元数据外发；
- 用户在前端打开了"隐私模式"，只对**当前会话**生效；
- 运维需要对**单个会话**紧急停止元数据外发，而不是整条连接（连接级熔断会殃及所有会话）；
- 灰度/AB：只对部分请求开启 meta 依赖的能力。

这些场景的共同点：**决策者是应用，决策点是请求**。

### 4.2 落点：RuntimeContext 是最自然的承载

meta 的**内容**本来就来自 `RuntimeContext.get(McpMeta.class)`；`McpTool` 在调用时已持有 `param.getRuntimeContext()`。在同一个 `RuntimeContext` 上叠加一个**请求级策略标志**，意味着：

- 应用（或其中间件）在发起 `agent.call(...)` 时注入，零新增管道；
- `RuntimeContext` 的 typed 层是 `ConcurrentMap<Class<?>, ConcurrentMap<String, Object>>`，`get(Class)` 未命中返回 `null` —— 天然提供"未表态"三态；
- `RuntimeContext` 本身是 per-call、线程安全的属性包，与"请求级"生命周期精确对齐。

**推荐的 API 形态**（独立策略载体，不混入 `McpMeta`）：

```java
public enum McpMetaPolicy { DEFAULT, ALLOW, DENY }

// 应用侧：请求前注入（中间件、auth 过滤器、agent 装配处均可）
runtimeContext.put(McpMetaPolicy.class, McpMetaPolicy.DENY);

// 框架侧：McpTool.callAsync 内读取（示意）
McpMetaPolicy p = param.getRuntimeContext() != null
        ? param.getRuntimeContext().get(McpMetaPolicy.class)
        : null;
boolean requestAllowed = (p == null || p == McpMetaPolicy.DEFAULT || p == McpMetaPolicy.ALLOW);
```

**落点权衡（为什么不用别的方案）**：

| 备选 | 否决理由 |
|------|---------|
| 塞进 `McpMeta` 自身 | 内容与策略混为一谈；`McpMeta` 常被预构建复用（会话级生命周期），而请求级决策 per-call 变化，生命周期错位 |
| 字符串 key（`ctx.put("io.agentscope.mcp.propagateMeta", ...)`） | 可行但弱类型、无编译期保护，且 string 层是 legacy/generic 扩展位 |
| 直接存 `Boolean` | `null=absent` 语义可行，但枚举自文档化更强，为将来扩展（如 `ID_ONLY`）留位 |

### 4.3 语义定义（四条必须钉死的规则）

1. **只能收紧，不能放开。** 请求级加入 AND 链：

   ```text
   发送 meta ⟺ 连接级 && 工具级 && 请求级放行(absent/DEFAULT/ALLOW 视为放行)
   ```

   请求级**永远不能突破连接级天花板**。否则任何能写 `RuntimeContext` 的代码（插件、子 agent、用户工具）都能绕过部署期策略，整个出口管控模型崩塌。

2. **absent / DEFAULT = 不表态，回落三级静态决策。** 存量应用零影响，完全向后兼容。

3. **DENY 与现有"关闭"语义逐字节一致**：`metaMap = null`，`_meta` 字段整体省略——用户 `McpMeta` 条目与框架 `toolCallId` **一并拦截**（fail-closed，与 `McpTool:236` 现行为对齐）。

4. **`toolCallId` 耦合问题（v1 保持耦合）。** `toolCallId` 是框架生成的关联标识而非用户数据，理论上可以"拒发用户 meta、仍发 toolCallId"。但把一个开关拆成两个维度会让决策矩阵翻倍、心智负担陡增。v1 维持"全发或全不发"；若未来确有"跨进程追踪但隐私合规"的组合需求，再以枚举新值（如 `ID_ONLY`）平滑扩展，不破坏现有语义。

### 4.4 跨 subagent 传播：继承是现状，必须显式文档化

这是本设计中最容易想当然、也最需要钉死的一点。**源码事实**（`SubAgentTool`）：

```java
// SubAgentTool.callAsync：取的是父级调用链传入的 RuntimeContext 实例
RuntimeContext runtimeContext = param.getRuntimeContext();          // :164
...
reActAgent.call(List.of(userMsg), runtimeContext);                  // :383（stream 同理）
```

子 agent 的 ReAct 循环收到的是**同一个 `RuntimeContext` 实例**，其内部工具调用的 `param.getRuntimeContext()` 也是它。由此推出三条结论：

1. **父级的 `McpMeta` 今天就会随子 agent 的 MCP 调用发出**（受三级开关约束）——这是现状行为，不是本设计引入的新风险，但官方文档必须明说。
2. **请求级 DENY 天然继承**：父级在调用前放入 `DENY`，子 agent 的所有 MCP 工具调用同样不发 meta。安全上是增益，符合"父级收口"的直觉，无需额外代码。
3. **请求级 ALLOW 同样继承**：父级放行的这一次调用，子 agent 的**独立**工具调用也同样放行。若应用需要隔离（子 agent 的下游调用重新受静态三级约束），在派发子 agent 前派生新上下文并覆盖标志：

   ```java
   RuntimeContext child = RuntimeContext.builder(parentCtx)
           .put(McpMetaPolicy.class, McpMetaPolicy.DEFAULT)  // 覆盖为不表态
           .build();                                          // Builder.from 浅拷贝属性，不污染父级
   ```

   派生实例共享 `runId`（有意为之，用于 `agent_spawn` 链路关联），但属性是各自独立的 map，子级覆写不影响父级。

**设计取舍**：默认**继承**（与 `McpMeta` 现状一致、零惊喜、DENY 侧天然安全），把"派生覆写"作为逃生门写进文档——而不是在框架层自动剥离（自动剥离会破坏"父上下文对子 agent 可见"的既有心智模型，且"剥离开关"自身又引入一层新语义）。

### 4.5 热路径开销

`callAsync` 是每一次工具调用的必经路径，请求级检查必须近乎免费：

- **现状成本**：2 次布尔读（工具级字段 + 连接级 `volatile`）→ 命中时 `buildMetaMap`（1 次 CHM 查找 + 1 次 `HashMap` 拷贝）。
- **新增成本**：1 次 `ctx.get(McpMetaPolicy.class)` ≈ 2 次 `ConcurrentHashMap` 无锁读（外层按类型定位 + 内层取值），数十纳秒量级。
- **短路顺序是关键**：先算静态 `propagateMeta && wrapper.isPropagateMeta()`，为 `false` 时**跳过** `RuntimeContext` 查找——静态关闭的部署，请求级检查成本为零。
- **DENY 路径反而更省**：跳过 `buildMetaMap` 的 map 分配，比今天"全开"路径开销更低。
- **实现禁忌**：调用路径上不构建策略对象、不加锁、不引入 `ThreadLocal`、不每次调用分配包装类型。

### 4.6 生效时机与并发语义

- **实时生效**：标志在每次工具调用 dispatch 时实时读取（与连接级 `volatile` 同款性质）。应用在两次调用之间改标志，下一次调用立即生效。
- **同一轮并行工具调用之间不保证快照一致**：同一 ReAct 轮次里的多个并行工具调用各自独立读标志。若应用在调用进行中并发修改标志，不同调用可能观察到不同值。需要"本轮内一致"的语义，由应用侧自行保证（如以中间件在轮次开始时冻结决策）。
- **读取点唯一**：仅在 `McpTool.callAsync`；权限审批（`PermissionDecision`）等前置链路不受该标志影响——请求级管的是"meta 出不出得去"，不是"这次调用能不能发生"。

---

## 5. 完整决策矩阵

| 连接级 | 工具级(含注册默认) | 请求级 | 结果 |
|:---:|:---:|:---:|------|
| OFF | 任意 | 任意 | ❌ 不发送（天花板） |
| ON | OFF | 任意 | ❌ 不发送 |
| ON | ON | DENY | ❌ 不发送（`_meta` 整体省略） |
| ON | ON | absent / DEFAULT / ALLOW | ✅ 发送全部 meta（含 `toolCallId`） |

记忆口诀：**四级 AND，逐级收紧；默认放行，一票否决。**

---

## 6. 典型场景与最佳实践

| 场景 | 做法 | 用到的层级 |
|------|------|-----------|
| 按租户合规 | auth 中间件解析请求 → `ctx.put(McpMetaPolicy.class, ...)` | 请求级 |
| 用户隐私开关 | 前端设置映射为 per-request `DENY` | 请求级 |
| 单会话紧急熔断 | 对该会话上下文注入 `DENY`，不碰连接级（其他会话不受影响） | 请求级 |
| 全线紧急停止 | `clientWrapper.setPropagateMeta(false)` | 连接级热切换 |
| 内部 MCP Server 才发 meta | 注册时按 server 归属设置工具/注册级 | 注册级/工具级 |
| 子 agent 委派收紧 | 派发前 `RuntimeContext.builder(parent)` 派生并覆写标志 | 请求级 + 派生上下文 |

最佳实践三则：

1. **防御纵深**：生产环境对不可信 MCP Server 一律连接级 OFF，需要 meta 能力的内部 Server 单独开——让请求级只做"收紧"，永远不承担"放行不可信连接"的职责。
2. **中间件收口**：不要在业务代码里到处写 `ctx.put(McpMetaPolicy...)`；统一在 auth/合规中间件一处决策，便于审计。
3. **元数据最小化**：开关只是兜底，`McpMeta` 里放的内容本身应当最小化——能不放的字段就不放。

---

## 7. 排障速查

| 症状 | 排查顺序 |
|------|---------|
| meta 没到对端 | ① 连接级是否 OFF（最常见）→ ② 工具/注册级是否 OFF → ③（请求级实现后）`RuntimeContext` 是否带 `DENY` → ④ 是否经过子 agent（确认标志继承是否符合预期） |
| 不该发的 meta 发出去了 | ① 连接级是否忘记关 → ② `RuntimeContext` 是否被中间件意外放入 `McpMeta` → ③ 子 agent 是否继承了父级放行的标志 |
| 开关改了没生效 | 连接级/请求级均为实时读，无需重连；检查是否改在了另一个 `McpClientWrapper` / 另一个 `RuntimeContext` 实例上 |

---

## 8. 实现状态

| 能力 | 状态 | 验证方式 |
|------|------|---------|
| 连接级开关 + 热切换 | ✅ 已实现 | 实机三阶段验证：统一静默 → 热切换恢复 → 再静默 |
| 注册级/工具级解析（`③??②??true`） | ✅ 已实现 | 源码 `McpClientManager:332-341`；实机验证工具级覆盖注册级 |
| 生效 AND（`McpTool:235`）与 fail-closed | ✅ 已实现 | 实机验证连接级天花板、防呆 `IllegalArgumentException` |
| **请求级动态控制（`McpMetaPolicy`）** | 🔧 **设计提案，未实现** | 本文 §4 为目标设计，含语义、传播、开销三项核心决策 |

> 请求级设计若落地，需同步：`McpTool.callAsync` 短路读取、`SubAgentTool` 传播行为回归测试、以及本文档 §4.4/§4.5 语义的单元测试固化。
