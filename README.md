# VoyageMind Backend

Spring Boot 4 + Java 21 后端，负责认证、行程规划、异步任务、RAG 证据、高德数据、行程版本和管理端接口。

## 运行依赖

- MySQL 8+
- RabbitMQ 4+（`tripstar.tasks.transport` 默认是 `rabbit`）
- AI Provider API Key（生成行程时）
- 高德 Web Service Key（POI、天气和路线）
- Milvus/Embedding 可选；不可用时进入关键词检索降级

Redis 已从运行依赖中移除。登录会话、任务检查点、快照和版本均由 MySQL 保存。

## 主要模块

```text
src/main/java/com/zkry/
├── controller/       REST 接口
├── service/          行程、规划、权限和管理用例
├── task/             Rabbit Worker、任务状态、重试和死信
├── memory/           工作记忆、用户事实、行程事件和归并
├── integration/      AI、高德、Embedding、Milvus 适配器
├── domain/           DTO、VO、实体和枚举
└── mapper/           MyBatis-Plus 数据访问
```

`TaskExecutionEngine` 是规划、AI 修改和知识任务的统一工作流分发入口。Rabbit 消费者负责领取任务、心跳、确认和失败重试，MySQL `trip_task` 是任务状态权威。`LocalTaskDispatcher` 仅用于测试或显式 `TASK_TRANSPORT=local` 的兼容场景。

## 行程生命周期

```text
TripRequest
  → 规范化约束与记忆
  → RAG Context Pack
  → 高德事实核验
  → Planner 生成结构化 TripPlan
  → 质量门禁与确定性修复
  → MySQL 快照 + trip_plan_version
  → 工作台局部修改（baseVersion 乐观锁）
```

修改默认返回局部操作；只有增加新事实、酒店或城市时才重新检索并调用工具。每次成功修改递增版本，过期 `baseVersion` 会被拒绝。

## 配置

```powershell
Copy-Item .env.example .env
```

至少设置：

```dotenv
DB_URL=jdbc:mysql://localhost:3307/tripstar?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USERNAME=root
DB_PASSWORD=
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=tripstar
RABBITMQ_PASSWORD=tripstar-dev
TASK_TRANSPORT=rabbit
```

完整变量见 [.env.example](.env.example)。密钥、Cookie 和 `logs/ai-trace` 不得提交到仓库。

## 启动

从项目根目录启动基础设施：

```powershell
docker compose up -d mysql
docker compose --profile rabbit up -d rabbitmq
```

然后构建并运行：

```powershell
mvn -DskipTests package
java -jar target\voyagemind-server-0.0.1-SNAPSHOT.jar --server.port=18081
```

健康检查：`GET http://localhost:18081/health`。

IDEA 的工作目录必须是当前 `backend` 目录，不能配置成 `backend\backend`。

## 测试

```powershell
mvn test
```

当前测试覆盖任务重试、规划上下文、AI 局部修改、删除权限和行程修复。真实广州端到端生成还需要本机 Docker、MySQL、RabbitMQ 以及有效的 AI/高德配置。

## 主要接口

| 接口 | 说明 |
| --- | --- |
| `POST /api/auth/login` | 登录并创建 MySQL 会话 |
| `POST /api/trip/plan` | 创建异步规划任务 |
| `GET /api/trip/status/{taskId}` | 查询任务阶段、进度和结果 |
| `GET /api/trip/history` | 查询已完成行程 |
| `GET /api/trips/{planId}/workspace` | 读取行程工作台 |
| `PUT /api/trips/{planId}` | 带 `baseVersion` 更新行程 |
| `POST /api/trips/{planId}/ai-modifications` | 提交 AI 局部修改 |
| `POST /api/routes/amap` | 计算高德路线 |
| `POST /api/knowledge/search` | 检索知识证据 |

## 许可证

GPL-2.0
