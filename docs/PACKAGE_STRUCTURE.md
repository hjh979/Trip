# 后端包结构规范

后端采用单模块 Spring Boot 和常用分层结构。主包固定为 `com.zkry`，新代码应按职责放入以下目录。

## 目录与职责

| 包 | 放置内容 | 命名示例 |
| --- | --- | --- |
| `controller` | HTTP 请求入口，只做参数接收、校验和调用 Service | `TripController` |
| `service` | 业务编排、事务边界、领域流程 | `TripTaskService` |
| `service.impl` | Service 接口实现；没有复用需要时可直接使用具体 Service | `FavoriteServiceImpl` |
| `mapper` | MyBatis / MyBatis-Plus 数据访问接口 | `FavoriteMapper` |
| `domain.entity` | 与数据库表对应的实体 | `Favorite`、`BaseEntity` |
| `domain.dto` | 请求参数、服务间数据传输对象 | `TripRequest` |
| `domain.vo` | 返回给前端的视图对象 | `TripPlanResponse` |
| `config` | Spring Bean、Web、MyBatis、Redis、Jackson 配置 | `RedisConfig` |
| `common.constant` | 全局常量和固定消息 | `TripTaskMessages` |
| `common.exception` | 业务异常和全局异常处理 | `BizException` |
| `common.response` | 统一接口响应 | `R` |
| `common.util` | 无业务状态的通用工具 | `JsonUtils` |
| `integration.ai` | 大模型、Agent、Prompt、Trace | `AiAgentService` |
| `integration.amap` | 高德 API、POI、天气、酒店工具 | `AmapTravelTools` |
| `integration.xhs` | 小红书读取、签名、解析和工具调用 | `XhsContentService` |
| `websocket` | WebSocket Handler | `TripTaskWebSocketHandler` |

## 依赖方向

```text
Controller -> Service -> Mapper -> Entity
                    -> Integration

Controller <-> DTO / VO
所有层都可以使用 Common
```

约束：

1. Controller 不直接调用 Mapper，也不放复杂业务逻辑。
2. Service 不依赖 Controller。
3. Entity 只表达持久化数据，不直接作为复杂接口响应。
4. DTO 用于输入和内部传输；VO 用于前端输出。
5. Redis、MyBatis、Jackson 等技术配置统一放 `config`，不再创建独立 Maven 模块。
6. AI、高德和小红书属于外部系统，通过 `integration` 隔离，业务 Service 负责组织调用。

## 新功能示例

增加“用户行程收藏”时建议创建：

```text
controller/FavoriteController.java
service/FavoriteService.java
service/impl/FavoriteServiceImpl.java
mapper/FavoriteMapper.java
domain/entity/Favorite.java
domain/dto/FavoriteCreateRequest.java
domain/vo/FavoriteVO.java
```
