# Sparse Symmetric Eigensolver Service

一个可本地调用（HTTP / 内嵌服务）的数值计算服务，用于求解**大规模实对称稀疏矩阵**的
若干最小（或落在给定区间内的）特征值及对应特征向量。基于带全再正交的 Lanczos 迭代，
全程使用压缩稀疏行（CSR）格式，**不做任何稠密化**。

## 1. 算法与假设

- **矩阵格式**：仅接受 CSR 上三角。对每个存储项 `(i, j, v)` 要求 `j >= i`；
  对角线存一次，严格上三角项利用对称性 `A[j,i] = A[i,j]`。稀疏矩阵-向量乘（SpMV）
  时间 `O(nnz)`、工作内存 `O(n)`，绝不分配 `n×n` 稠密结构。
- **对称性假设**：输入矩阵必须是实对称矩阵。服务不接收下三角项（这样可显式、无歧义
  地拒绝“非对称上三角”输入），非对角贡献由 SpMV 对称地同时累加到两端。
- **Lanczos 迭代**：构造 Krylov 子空间 `K_m = span{q1, A q1, ..., A^{m-1} q1}`，
  投影得到对称三对角矩阵 `T_m`。采用**两轮经典 Gram–Schmidt 全再正交**保证基底
  数值正交；这需要 `O(m·n)` 内存保存完整基底。
- **Ritz 提取**：`T_m` 的特征值（Ritz 值）由 **Sturm 序列二分法**计算，所需的
  最小若干特征向量由对三对角矩阵的**逆迭代**（Thomas 求解 + 再正交 + 确定性重启）
  得到，然后通过 `y = Q_m s` 还原为原空间向量。该组合确定性强、对极值特征值稳定。
- **确定性初值**：起始向量 `q1` 由请求 `seed` 经固定 64 位 LCG 生成并归一化。
  因此**相同（输入、种子、配置）⇒ 逐位一致的输出**。
- **收敛判据**：Ritz 对 `(θ, y)` 的后验残差界为 `|β_{m+1}|·|s_m|`（`s` 为 `T_m`
  的特征向量末分量）。提交前还会计算**真实残差 `‖Ay − θy‖₂`** 双重把关，避免在
  Krylov 子空间耗尽时给出伪成功。

## 2. 向量符号与归一化规则（固定）

- 归一化：`‖y‖₂ = 1`。
- 符号：取绝对值最大的分量为**非负**；绝对值相同（并列）时取**下标最小**的分量。
  该规则对每个返回向量固定，保证跨运行、跨重启符号一致。
- 排序：按特征值升序返回；区间过滤时返回区间内最小的 `nev` 个。

## 3. 误差语义

- 每个结果 `EigenPairResult` 含：
  - `eigenvalue`：近似特征值；
  - `residual`：**真实相对/绝对残差 `‖Ay − θy‖₂`**（绝对值，单位与矩阵一致）；
  - `converged`：是否满足 `residual <= tolerance`；
  - `eigenvector`：归一化、符号固定的近似特征向量。
- 只有当请求的**每一个**特征对都满足容差时，状态才是 `CONVERGED`。
- **未收敛或数值退化不会被包装成成功**：
  - 迭代预算用尽仍未达标 → `FAILED / NOT_CONVERGED`；
  - Krylov 不变子空间提前闭合且合格对不足 → `FAILED / NUMERICAL_BREAKDOWN`；
  - 三对角/逆迭代出现非有限量或无法增长 → `NUMERICAL_BREAKDOWN`；
  - 超时 → `FAILED / TIME_LIMIT_EXCEEDED`；取消 → `CANCELLED`。
  失败响应仍保留可诊断信息：已完成迭代数、最后一次的 Ritz 值与残差估计。

## 4. 请求参数

| 参数 | 含义 | 默认 / 约束 |
|---|---|---|
| `requestId` | 客户端给定的幂等标识 | 匹配 `[A-Za-z0-9._-]{1,128}` |
| `dimension` | 矩阵阶 `n` | >0，且 ≤ `solver.max-dimension` |
| `rowPtr` | CSR 行指针，长度 `n+1`，`rowPtr[0]=0`，单调不减 | 必须与条目数一致 |
| `colIndex` / `values` | 列下标 / 数值（仅上三角） | 等长，列升序、无重复、有限 |
| `nev` | 需要的特征值数量 | `1..n` |
| `tolerance` | 收敛容差（作用于残差） | 有限，`(0,1)`，默认 `1e-8` |
| `maxIterations` | Lanczos 步数上限（迭代资源） | `nev..n` 且 ≤ 全局上限 |
| `intervalLower/Upper` | 区间过滤（闭区间，可空） | 有限，下界 ≤ 上界 |
| `seed` | 确定性起始向量种子 | 任意 long，默认 `1` |
| `timeLimitMs` | 单请求墙钟时间预算 | `(0, solver.max-time-limit-ms]` |
| `snapshotIntervalMs` | 子空间快照周期 | >0 |

## 5. 并发、资源上限与回收

- 固定大小线程池（`solver.max-concurrency`）+ 有界等待队列（`solver.queue-capacity`），
  超出容量返回 `RESOURCE_LIMIT`（HTTP 429），不会拖垮服务或饿死小请求。
- 每个请求有独立的：
  - **内存上限** `solver.per-request-memory-bytes`：依据完整基底 `(m+1)·n·8` 字节
    在开工前预估，超限直接拒绝（`RESOURCE_LIMIT`）；
  - **时间上限** `timeLimitMs`：迭代间协作检查，到点标记 `TIME_LIMIT_EXCEEDED`；
  - **迭代上限** `maxIterations`。
- 维度 / 非零元 / 请求体量上限（`max-dimension`、`max-non-zeros`、
  `max-request-bytes`）在解析重负载前即被检查。
- **取消 / 超时**：中断工作线程、从注册表移除并释放并发槽位；取消会清除中间快照，
  不留部分结果，也不阻塞其他请求。

## 6. 快照与进程重启恢复

- 每完成一个 Lanczos 步，到达 `snapshotIntervalMs` 周期时，将**完整 Krylov 基底 `Q`、
  α、β、配置指纹**以 Java 二进制序列化（double 精确保留）经“临时文件 + 原子改名”
  落盘到 `<snapshot-dir>/<requestId>/state.bin`。
- 进程重启后，用**相同 `requestId` 与相同输入/参数**再次提交，即从最近快照的
  Krylov 维度继续，**不重算已完成迭代**；恢复后的最终结果与一次连续运行**逐位一致**。
- 用不同输入/参数复用同一 `requestId` 会被拒绝（`SNAPSHOT_MISMATCH`）。
- 收敛后结果被写入快照作为**结果日志（journal）**：再次提交同一 `requestId` 直接
  返回已提交结果，不重算、不重复产出。

## 7. HTTP 接口

- `POST /api/eigensolve?wait=false|true`：提交（或恢复）一个请求。
  - `wait=false`：立即返回当前状态（`PENDING/RUNNING/...`）；
  - `wait=true`：阻塞至完成或时间预算到点。
- `GET /api/eigensolve/{id}`：查询状态/结果（含已落盘的历史结果）。
- `POST /api/eigensolve/{id}/cancel`：协作取消。

错误体：`{ "requestId", "code", "message", "timestamp" }`，其中 `code` 为稳定错误码。

### 错误码 → HTTP

| 错误码 | HTTP | 含义 |
|---|---|---|
| `INVALID_DIMENSION` / `INVALID_POINTER` | 400 | 维度/CSR 指针非法 |
| `DUPLICATE_INDEX` / `INDEX_OUT_OF_RANGE` | 400 | 列重复 / 越界 |
| `NON_FINITE_VALUE` | 400 | NaN/Infinity |
| `NON_SYMMETRIC_UPPER_TRIANGLE` | 400 | 出现下三角（非对称输入） |
| `INVALID_NEV` / `INVALID_TOLERANCE` / `INVALID_MAX_ITERATIONS` / `INVALID_INTERVAL` / `INVALID_CONFIG` / `INVALID_SEED` | 400 | 参数非法 |
| `MATRIX_TOO_LARGE` | 413 | 维度/非零元/体量超限 |
| `RESOURCE_LIMIT` | 429 | 并发/队列/内存预算不足 |
| `JOB_NOT_FOUND` | 404 | 未知请求 |
| `JOB_ALREADY_EXISTS` | 409 | 活动 requestId 冲突 |
| `SNAPSHOT_MISMATCH` | 409 | 快照与新输入/参数不一致 |
| `NOT_CONVERGED` / `NUMERICAL_BREAKDOWN` | 422 | 同步调用下求解未成功 |
| `TIME_LIMIT_EXCEEDED` | 503 | 时间预算耗尽 |
| `SNAPSHOT_CORRUPT` / `INTERNAL_ERROR` | 500 | 快照损坏 / 内部错误 |

异步（`wait=false`）求解类失败通过响应体的 `status=FAILED` 与 `errorCode` 表达，
而非 HTTP 错误，便于轮询。

## 8. 审计

拒绝原因与资源计量（维度、非零元、估算字节、耗时）通过 `solver.audit` logger 输出
结构化记录（`ACCEPTED` / `REJECTED` / `FINISHED`），便于接入日志管道审计。

## 9. 配置项（`application.properties`，前缀 `solver.`）

`max-dimension`、`max-non-zeros`、`max-request-bytes`、`per-request-memory-bytes`、
`max-concurrency`、`queue-capacity`、`default-time-limit-ms`、`max-time-limit-ms`、
`default-max-iterations`、`max-iterations`、`snapshot-interval-ms`、`snapshot-dir`。

## 10. 构建与测试

```bash
mvn clean test     # 50 个测试，覆盖：
#  非法输入(各类错误码)、不收敛、数值退化、并发执行、取消与超时回收、
#  崩溃后从快照恢复且逐位一致、确定性/可复现、区间过滤、资源上限、Web 错误码
mvn spring-boot:run
```

### 示例请求

```json
{
  "requestId": "demo-1",
  "dimension": 3,
  "rowPtr": [0, 2, 3, 4],
  "colIndex": [0, 1, 1, 2],
  "values": [2.0, -1.0, 2.0, 2.0],
  "nev": 1,
  "tolerance": 1e-9,
  "maxIterations": 3,
  "seed": 42
}
```

## 已知限制

- 全再正交 Lanczos 内存随 `m·n` 增长；通过 `per-request-memory-bytes` 与
  `max-iterations` 约束，适合求解远小于 `n` 的少数极值特征对。
- 三对角特征值使用 Sturm 二分（求全部 Ritz 值成本 `O(m·steps)`）；逆迭代只作用于
  所需的最低若干秩，避免对谱中部聚集特征值做病态求解。
