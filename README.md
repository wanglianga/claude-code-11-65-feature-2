# 旱灾理赔时点特征服务（Agri Point-in-Time Feature Service）

基于 **Java 21 + Spring Boot 3.5 + PostgreSQL 18** 的可本地运行特征服务，用于农业保险旱灾理赔核验。核心原则只有一条：

> **特征取数以「可用时间」而非「观测时间」为准；历史案件保留评分快照；后到数据只能通过受控回填生成复核候选，绝不直接覆盖旧理赔记录。**

---

## 1. 两套时间语义

仓库中每条记录都同时保存两个时钟：

| 字段 | 含义 |
| --- | --- |
| `observed_at` | 现象实际发生的时间（影像采集、气象时段、查勘/申报时间） |
| `ingested_at` | 平台真正可以看到这条记录的时间（入库时间） |

修订不是 `UPDATE`，而是**追加新版本**并保留 `revision_of_id` 链。取数时统一使用：

```
ingested_at <= availability_ceiling
```

- 原始评分：`availability_ceiling = scoring_cutoff`
- 受控回填：`availability_ceiling = 修订事件入库时间`，且运行模式为 `CONTROLLED_BACKFILL`，**不写快照**，只生成复核候选。

这样，一景 2024-06-18 采集、2024-06-25 才入库的影像，在 2024-06-22 截止的案件中会被明确排除，并留痕在运行台账里。

---

## 2. 功能模块

| 模块 | 说明 | 主要接口 |
| --- | --- | --- |
| 特征目录 | 维护公式、来源类型、可用时滞（`availability_lag_seconds`）、审批版本；已审批版本不可改公式（数据库触发器 + 服务层双重保护） | `POST /api/catalog/features`、`POST /api/catalog/features/{code}/versions`、`POST .../versions/{v}/approve` |
| 时点数据仓 | 影像 / 气象 / 地块边界 / 播种申报的观测时间、入库时间、修订链 | `POST /api/warehouse/scenes|weather|declarations|parcels/{id}/boundaries` |
| 权属版本库 | 保存权属人、边界来源、生效日期、观测/入库时钟与审批状态；支持按受理时点 as-of 解析 | `POST /api/tenure/versions`、`POST .../approve|reject`、`GET /api/tenure/parcels/{id}/versions`、`GET .../as-of?at=` |
| 边界重叠分析器 | 计算同一地块新旧版本及相邻地块边界的相交面积、双方占比，标记是否超政策容差，输出重叠图谱 | `POST /api/boundary/parcels/{id}/overlap-analyses`、`GET .../overlap-graph` |
| 案件几何冻结器 | 受理时把“已批准且当时可见”的几何版本（含 GeoJSON 副本、权属、来源）固化为不可变行；后续测绘/审批/回填并发也读这份快照 | `GET /api/boundary/cases/{caseNo}/geometry-freeze` |
| 像元分摊器 | 交叠像元按确定性规则归属/排除；跨地块且超容差区域排入待裁定，禁止并列归属两笔申请，逐像元留痕 | `GET /api/disputes/pixel-allocations` |
| 争议处理台 | 人工裁定（归属 A/B、双方排除、要求复测）与撤回只追加；撤回后再复核必新建 `DISPUTE_RESAMPLING` 取样运行，原裁定与原面积永久保留 | `POST /api/disputes/{id}/rulings|withdrawal|review-requests` |
| 回填编排器 | 按灾害时点生成地块级特征运行，FIFO 队列 + 租约式认领，可同步重放 | `POST /api/cases/{caseNo}/runs`、`POST /api/runs/{id}/execute`、`GET /api/runs/queue/stats` |
| 穿越检测器 | 核验每个输入在评分截止前是否可用，产出持久化报告与逐条违规 | `GET /api/runs/{id}` → `leakage` |
| 评分快照 | 冻结案件引用的特征值与定义版本，数据库层禁止 UPDATE/DELETE | `GET /api/snapshots/{id}`、`GET /api/cases/{caseNo}/snapshots` |
| 影响分析 | 定位一次数据修订影响哪些案件，受控回填并生成复核候选 | `POST /api/impact/analyses/by-revision/{eventId}`、`GET /api/review/candidates` |
| 案件追溯 / 复现 | 案件运行历史、输入台账，一键按原截止时间重放并比对快照 | `GET /api/cases/{caseNo}/trace`、`POST /api/cases/{caseNo}/reproduce` |
| 结案 | 评分完成后结案（`SCORED → PAID`），结案后任何状态回退都被拒绝 | `POST /api/cases/{caseNo}/settle` |

Swagger UI：<http://localhost:8080/swagger-ui.html>　OpenAPI JSON：<http://localhost:8080/v3/api-docs>

---

## 3. 三条强制约束的落地方式

### 3.1 晚到影像不得进入原案件评分

`AsOfSelector.selectSatellite` 分两段查询：

1. `ingested_at <= ceiling` → 可用场景；
2. `ingested_at > ceiling` → 写入台账，判定 `EXCLUDED_LATE_AVAILABILITY`，并产生 `LATE_SCENE_EXCLUDED` 告警。

即使采集时间早于事故、云量更低，也不会进入 `ndvi_mean` 等聚合变量。样例中 `SCENE-2024-06-18-B`（云量 3%，NDVI≈0.21）就是这样被挡在 2024-06-22 截止之外的。

若窗口内所有影像都晚到、导致必填特征无法计算，运行状态为 `INSUFFICIENT_DATA` 或 `LEAKAGE_BLOCKED`，案件转 `UNDER_REVIEW`，**不写快照**。实测：一个缺少可用影像的案件运行得到 `BLOCKED`（`NO_ELIGIBLE_SCENE` 告警），快照数为 0。

### 3.2 两个地块边界版本重叠时按案件锁定版本隔离

`PixelAllocatorService` 对落在运行锁定边界内的每个栅格像元（scene cell）给出三类结论并写入 `pixel_allocation`：

- **`ATTRIBUTED`**：仅一个边界覆盖（`SINGLE_BOUNDARY_OWNER`），或交叠在政策容差内、由确定性优先级（案件锁定版本优先，其次 parcel id、版本号）判给本运行（`DETERMINISTIC_PRIORITY` / 争议裁定后的 `DISPUTE_RULING_GRANT`）；
- **`EXCLUDED`**：容差内交叠判给了另一地块/版本，或争议裁定要求双方排除（`DISPUTE_RULING_EXCLUDE_BOTH`），台账判定 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`；
- **`PENDING_ARBITRATION`**：**跨地块**且交叠面积占比超过政策容差（默认 5%）。该像元对两笔申请都不计入，自动开立/复用一条 `boundary_dispute`，台账判定 `EXCLUDED_PENDING_ARBITRATION`，等待人工裁定。

判定只依赖已存储的登记/案件/裁定数据，与运行先后顺序无关，因此重放与再次取样都可复现。样例中 P-1001 的 v1 与 v2 是**同一地块**的新旧版本，共享像元不会触发跨农户重复理赔，仍走锁定优先；而 P-1001 v1 与相邻农户 P-1003 v1 的交叠约 32%（>5%），共享像元进入待裁定。

> 边界变体运行使用 `REPLAY` 模式 + `boundary_variant = true`，永不覆盖案件快照。

### 3.4 权属版本库与案件几何冻结（按受理时已批准几何为基线）

- `parcel_tenure_version` 保存权属人、边界来源（`AGRI_REGISTRY / RE_SURVEY / TRANSFER_DEED …`）、生效日期、观测/入库时钟与审批状态。流转或复测只**追加新版本**，旧版本不被覆盖。
- 案件受理时 `GeometryFreezeService` 把 as-of（`approved_at <= cutoff` 且 `ingested_at <= cutoff`）已批准权属引用的边界，连同完整 GeoJSON 副本、面积、容差写入 `case_geometry_freeze`，并由数据库触发器禁止 UPDATE/DELETE。
- 之后每次评分/重放/争议取样，`RunExecutor` 都优先读取该冻结几何；即使权属审批与回填并发发生，已受理案件的受灾面积也不会被最新登记图无声改变。新测绘成果只能通过争议台发起后续复核。
- 同一地块在不同案件中因此可能出现不同面积快照——这是刻意设计：查勘员可通过冻结行与 as-of 接口解释版本差异。样例中 P-1001 的流转权属 v2 在 2024-06-25 才批准，`DROUGHT-2024-0001`（截止 2024-06-22）始终锁定 v1；而 2026 年新受理的案件会解析到当前批准的 v2。

### 3.5 争议处理台与再次取样

- 超容差交叠自动产生 `boundary_dispute`（状态 `OPEN`）。人工可裁定 `GRANT_TO_A / GRANT_TO_B / EXCLUDE_BOTH / RE_SURVEY_REQUIRED`；裁定与撤回都只向 `boundary_dispute_ruling` **追加**，触发器禁止修改/删除。
- 裁定撤回后争议回到待裁定队列，已归属/排除的像元重新置为 `PENDING_ARBITRATION`。
- 每次发起复核都新建一条 `trigger_type = DISPUTE_RESAMPLING` 的取样运行（`boundary_review_request` 记录 `prior_ruling_id / prior_area_ha / prior_snapshot_id`），运行仍读受理冻结几何。原裁定与原面积结果永久保留，跨地块异议可回查到受理时锁定的边界材料。

### 3.3 已结案后的气象修订只生成复核候选

`ImpactService.analyze` 对每个有快照的案件判定：

- 修订在截止前已可用 / 快照实际引用了该版本 → `ALREADY_VISIBLE_AT_CUTOFF`
- 观测窗口与该案件 NDVI/降雨窗口不相交 → `NOT_RELEVANT`
- 否则 → 排队 `CONTROLLED_BACKFILL`（`LATEST_AVAILABLE`，天花板=修订入库时间），运行后可生成 `PENDING_REVIEW` 复核候选，包含 `baseline / candidate / delta`。

**冻结快照不写、已付款结论不动**。样例中 WS-001 的 2024-07-08 修订产生候选（`NDVI_DROUGHT` +0.142，`RAIN_ANOMALY` −9.4），快照哈希保持 `26b972d0…` 不变，案件状态仍为 `PAID`。

`review_candidate` 只允许 `PENDING_REVIEW → ACKNOWLEDGED / DISMISSED`，由理赔人员决定，属于流程动作而非自动改单。

案件状态只允许向前：`OPEN/QUEUED → SCORED → PAID`。受控回填、影响分析、复现失败等路径只会把 `OPEN/QUEUED/SCORED` 提升为 `UNDER_REVIEW`，不会把 `PAID` 降级。

---

## 4. 本地启动

### 4.1 直接运行（使用容器内已装运行时）

前置：PostgreSQL 18 已在 `127.0.0.1:5432`，库 `agri_pit`（用户/密码 `ccdev`）。若没有库，先建：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE agri_pit OWNER ccdev;"
```

构建并启动（首次启动自动执行 Flyway 迁移 + 写入演示样例）：

```bash
cd /workspace/65-feature-2
/opt/maven/bin/mvn -q -s docker/maven-settings.xml -DskipTests package
/opt/jdk-25/bin/java -jar target/agri-pit.jar
```

如需清空重来：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d agri_pit -c "drop schema public cascade; create schema public;"
```

数据库连接可用环境变量覆盖：`DB_URL`、`DB_USER`、`DB_PASSWORD`、`SERVER_PORT`、`APP_SEED_DEMO`。

### 4.2 Docker Compose（一键）

```bash
docker compose up --build
```

`app` 等待 `db` 健康后启动，`8080` 端口对外，样例自动写入。停止并清理：`docker compose down -v`。

> 本仓库交付了 `Dockerfile` 与 `docker-compose.yml`；当前隔离容器内没有 Docker 守护进程，因此本 README 的验证结论均来自 4.1 的直接运行方式。Docker 构建阶段使用 `docker/maven-settings.xml` 指向已配置的 Maven 镜像源。

---

## 5. 测试账号与接口鉴权

本项目为本地演示，**未启用鉴权**，所有接口匿名可访问，无需账号。若需要演示审批人字段，请传 `approvedBy` / `decidedBy`（如 `catalog-admin`、`reviewer`），仅作为审计记录。

---

## 6. 演示样例（启动即写入，幂等）

| 对象 | 内容 |
| --- | --- |
| 特征 | `NDVI_DROUGHT`（NDVI 亏缺，含离散度惩罚）、`RAIN_ANOMALY`（60 天降雨亏缺）、`CROP_EXPOSURE`（申报面积 × 生育期系数） |
| 地块 | `P-1001`（边界 v1/v2/v3，播种后流转 + 复测）、`P-1002`（对照）、`P-1003`（相邻农户，登记图与 P-1001 超容差交叠） |
| 权属 | P-1001 权属 v1（自营，2024-04 批准，引用边界 v1）、v2（流转，2024-06-25 批准，引用复测 v2）、v3（待批准）；P-1002/P-1003 各一条已批准权属 |
| 气象站 | `WS-001`（主）、`WS-002`（次） |
| 案件 | `DROUGHT-2024-0001`（P-1001，冻结 v1）、`DROUGHT-2024-0002`（P-1002）、`DROUGHT-2024-0003`（P-1003） |
| 灾时点 | 2024-06-20；评分截止 2024-06-22T00:00Z |
| 陷阱数据 | 晚到影像 `SCENE-2024-06-18-B`；晚到气象修订 `WS-001-W2024-06-13` rev 2（2024-07-08 入库） |
| 争议 | 种子运行即对 P-1001/P-1003 超容差交叠像元自动开立 `boundary_dispute`，可在 `/api/disputes` 查看 |

`GET /api/demo/scenarios` 会返回场景索引与当前计数。

---

## 7. 复现流程（验收脚本）

服务启动并可访问后执行：

```bash
./scripts/verify.sh                  # 时点正确性三约束 + 复现（默认 BASE=http://localhost:8080）
./scripts/verify-tenure-dispute.sh   # 权属 as-of、几何冻结、重叠图谱、争议裁定/撤回/再次取样
```

`verify.sh` 依次验证：

1. **晚到影像**：`DROUGHT-2024-0001` 原始运行含 `EXCLUDED_LATE_AVAILABILITY`，且没有任何 `ingestedAt > cutoff` 的可用输入；
2. **重叠边界**：对 v2 发起边界变体运行，出现 ≥1 条 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`，原快照哈希不变；
3. **气象修订**：先将案件结案为 `PAID`，影响分析给出 `RECOMPUTE_CANDIDATE` 并生成 `PENDING_REVIEW` 候选，案件仍为 `PAID`、仍只有 1 个冻结快照且哈希不变；
4. **复现**：`POST /api/cases/.../reproduce` 按原截止时间重放，`valuesMatch=true`。

`verify-tenure-dispute.sh` 依次验证（可重复执行，已裁定的争议会先自动撤回）：

1. **权属 as-of**：受理截止时点解析到权属 v1/边界 v1；截止后批准的流转权属 v2 只在更晚 as-of 可见；
2. **几何冻结**：受理案件冻结 v1 且含完整 GeoJSON 快照，记录冻结面积；
3. **重叠图谱**：P-1001 v1 ↔ P-1003 v1 被标记 `exceedsTolerance=true`，并自动产生争议与 `PENDING_ARBITRATION` 像元；
4. **裁定 → 复核**：`GRANT_TO_A` 后新建 `DISPUTE_RESAMPLING` 运行，争议像元按 `DISPUTE_RULING_GRANT` 归属，运行仍用冻结 v1；
5. **撤回 → 再复核**：原裁定与撤回都保留，第二次复核生成**另一条**新取样运行并仍引用原面积，撤回后像元回到待裁定。

手工操作示例：

```bash
# 查看案件与运行
curl -s localhost:8080/api/cases/DROUGHT-2024-0001 | jq .
curl -s localhost:8080/api/cases/DROUGHT-2024-0001/trace | jq .

# 看某次运行的输入台账与穿越报告
curl -s localhost:8080/api/runs/1 | jq '{leakage, inputs}'

# 触发气象修订的影响分析（先结案，模拟已付款）
curl -s -XPOST localhost:8080/api/cases/DROUGHT-2024-0001/settle \
  -H 'Content-Type: application/json' -d '{"settledBy":"claims-officer"}' | jq .
EV=$(curl -s "localhost:8080/api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION" | jq -r '[.[]|select(.revision_no==2)][0].id')
curl -s -XPOST localhost:8080/api/impact/analyses/by-revision/$EV | jq .
curl -s localhost:8080/api/review/candidates | jq .

# 复核决定（只记录人工结论，不改快照/赔付）
curl -s -XPOST localhost:8080/api/review/candidates/1/decisions \
  -H 'Content-Type: application/json' \
  -d '{"action":"ACKNOWLEDGE","decidedBy":"reviewer","note":"accepted for manual adjustment"}'

# 权属版本与受理时点解析
P1=$(curl -s localhost:8080/api/warehouse/parcels | jq -r '.[]|select(.parcel_code=="P-1001")|.id')
curl -s "localhost:8080/api/tenure/parcels/$P1/as-of?at=2024-06-22T00:00:00Z" | jq .
curl -s localhost:8080/api/boundary/cases/DROUGHT-2024-0001/geometry-freeze | jq .

# 重叠图谱与争议处理台
curl -s -XPOST localhost:8080/api/boundary/parcels/$P1/overlap-analyses \
  -H 'Content-Type: application/json' -d '{"tolerancePct":5.0,"scope":"BOTH"}' | jq .
curl -s localhost:8080/api/disputes?status=OPEN | jq .
DSP=$(curl -s localhost:8080/api/disputes?status=OPEN | jq -r '.[0].id')
curl -s -XPOST localhost:8080/api/disputes/$DSP/rulings -H 'Content-Type: application/json' \
  -d '{"ruling":"GRANT_TO_A","decidedBy":"arbiter-wang"}' | jq .
curl -s -XPOST localhost:8080/api/disputes/$DSP/review-requests -H 'Content-Type: application/json' \
  -d '{"requestedBy":"reviewer-chen","reason":"re-sample after ruling"}' | jq .
```

---

## 8. 数据模型要点

- `feature_definition` / `feature_definition_version`：定义与审批版本；`checksum` 记录公式摘要；已审批版本由触发器锁定不可改。
- `claim_case_feature_lock`：案件受理时锁定具体定义版本，之后目录再审批新版本也不影响历史案件。
- `feature_run`：`mode ∈ {ORIGINAL, REPLAY, CONTROLLED_BACKFILL}`，携带 `cutoff_at`、`availability_ceiling`、`boundary_version_id`、`boundary_variant`、`parent_run_id`。
- `feature_run_input`：输入台账（`observed_at`、`ingested_at`、`eligibility`、`reason`），是穿越检测与追溯的依据。
- `leakage_report` / `leakage_violation`：持久化检测结论与告警。
- `scoring_snapshot` / `snapshot_feature` / `snapshot_input`：冻结快照，`inputs_hash`（SHA-256）用于复现比对；触发器禁止修改。
- `data_revision_event`：所有修订事件，影响分析入口。
- `impact_analysis` / `impact_case` / `review_candidate`：影响范围、受控回填运行、待复核候选。
- `pixel_claim`：早期像元唯一归属表（V1），保留以记录历史运行；V2 起逐像元结论改由 `pixel_allocation` 承载。
- `parcel_tenure_version`（V2）：权属版本库——权属人、边界来源、生效日期、审批状态、观测/入库时钟；as-of 以 `approved_at`/`ingested_at` 推导，不改写旧版本状态。
- `case_geometry_freeze`（V2）：受理时固化的几何版本与 GeoJSON 副本，触发器禁止 UPDATE/DELETE。
- `boundary_overlap_analysis` / `boundary_overlap_pair`（V2）：重叠分析与图谱边（相交面积、双方占比、是否超容差、相交几何）。
- `pixel_allocation`（V2）：每个运行、每个像元的归属结论（`ATTRIBUTED / EXCLUDED / PENDING_ARBITRATION`）、规则码与关联争议。
- `boundary_dispute` / `boundary_dispute_ruling`（V2）：争议单与只追加的裁定/撤回历史（含保留面积与快照引用）。
- `boundary_review_request`（V2）：复核请求，每次都关联一条新的 `DISPUTE_RESAMPLING` 取样运行及原裁定/原面积。
- `feature_run.trigger_type`（V2 扩展）：新增 `DISPUTE_RESAMPLING`。

公式语言是一个受限表达式解析器（`FormulaExpression`）：支持 `+ - * /`、括号、数字、聚合变量，以及 `min/max/abs/round/floor/ceil/clamp/coalesce`。**不支持任意函数调用**，避免公式越权访问数据。变量只由通过时点筛选的输入填充。

---

## 9. 已验证内容（实际执行结论）

以下结论均为本机实际执行所得：

- `mvn test`：公式解析/求值单测通过（优先级、聚合变量、拒绝未知函数、除零返回 NaN）。
- `mvn package` 构建成功，`target/agri-pit.jar` 可启动。
- 启动后 Flyway 建表（V1 + V2 两个迁移）、样例写入成功；三个案件均由队列 worker 执行为 `SUCCEEDED` 并生成快照。
- `./scripts/verify.sh` 全绿：三条强制约束 + 复现流程全部通过。
- `./scripts/verify-tenure-dispute.sh` 全绿：权属 as-of 在受理截止解析到 v1、截止后流转解析到 v2；案件冻结 v1 且含几何快照；P-1001/P-1003 跨地块交叠被标记超容差并自动开立争议；`GRANT_TO_A` 后取样运行按裁定归属且仍用冻结 v1；撤回后再次复核生成**新的** `DISPUTE_RESAMPLING` 运行，原裁定（RULE + WITHDRAW 两行）与原面积 95.0613 ha 均保留，像元回到待裁定。
- 并发不变量实测：在已受理案件之后新增并审批一条指向全新边界 v4 的权属，原案件 `case_geometry_freeze` 仍为 v1/95.0613 ha，争议取样运行 `boundaryVersionId` 仍为 1；同期新受理的案件则解析到当前批准的 v2，证明不同案件保留各自面积快照。
- 容差可调实测：以 70% 容差重跑邻地分析时，P-1001 v1 ↔ P-1003 v1（约 32%）变为 `exceedsTolerance=false`。
- `GET /v3/api-docs` 返回 OpenAPI（含 `/api/tenure`、`/api/boundary`、`/api/disputes` 全部新路径），`/swagger-ui/index.html` 可访问。
- 数据库层保护实测：直接 `UPDATE scoring_snapshot`、`UPDATE case_geometry_freeze`、`UPDATE boundary_dispute_ruling` 分别抛出 immutable / append-only 异常；直接改已审批公式抛 "approved feature definition versions are immutable"。
- 目录审批实测：对已审批版本重复审批返回 `VERSION_NOT_DRAFT`；新增并审批 v2 后，历史案件 `lockedFeatures` 仍指向 v1，复现 `valuesMatch=true`。
- 状态保护实测：案件结案为 `PAID` 后执行影响分析和复现，状态保持 `PAID`，快照数与哈希不变。
- 数据不足路径实测：构造一个无可用影像的案件，运行为 `LEAKAGE_BLOCKED`（含 `NO_ELIGIBLE_SCENE`），快照数为 0，案件转 `UNDER_REVIEW`。

未验证项：当前容器无 Docker 守护进程，`docker compose up` 未实际执行；已提供可构建的 `Dockerfile` / `docker-compose.yml`。

---

## 10. 目录结构

```
src/main/java/com/agri/pit/
  catalog/    特征目录、公式表达式、审批
  ingest/     时点数据仓写入与修订事件
  tenure/     权属版本库、审批、as-of 查询
  boundary/   边界重叠分析器、重叠图谱、案件几何冻结器
  dispute/    像元分摊器、争议处理台、复核取样请求
  cases/      案件受理、版本锁定（受理时触发几何冻结）
  runs/       运行队列、执行器（优先读冻结几何）、特征求值、worker
  leakage/    时点取数、像元分摊接入、穿越检测
  snapshots/  快照冻结、回填候选、复现比对
  impact/     修订影响分析、复核候选
  demo/       确定性样例与场景索引
src/main/resources/db/migration/V1__schema.sql
src/main/resources/db/migration/V2__tenure_geometry_governance.sql
scripts/verify.sh
scripts/verify-tenure-dispute.sh
Dockerfile / docker-compose.yml
```
