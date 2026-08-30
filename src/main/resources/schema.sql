CREATE TABLE IF NOT EXISTS trip_task (
    id BIGINT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    plan_id VARCHAR(64) NULL,
    idempotency_key VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    progress_text VARCHAR(500) NOT NULL DEFAULT '',
    request_json LONGTEXT NOT NULL,
    result_plan_id VARCHAR(64) NULL,
    result_version INT NULL,
    result_url VARCHAR(500) NULL,
    error_code VARCHAR(80) NOT NULL DEFAULT '',
    error_message TEXT NULL,
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 4,
    last_seq BIGINT NOT NULL DEFAULT 1,
    lock_version INT NOT NULL DEFAULT 0,
    processing_token VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    next_retry_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_task_task_id (task_id),
    UNIQUE KEY uk_trip_task_idempotency (owner_id, task_type, idempotency_key),
    KEY idx_trip_task_owner_updated (owner_id, deleted, update_time),
    KEY idx_trip_task_plan_active (plan_id, task_type, status, deleted),
    KEY idx_trip_task_dispatch (status, next_retry_at, deleted),
    KEY idx_trip_task_lease (status, lease_until, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_outbox (
    id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    publish_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token VARCHAR(64) NULL,
    claim_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NOT NULL DEFAULT '',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_outbox_event_id (event_id),
    KEY idx_message_outbox_publish (status, next_attempt_at, claim_until, deleted),
    KEY idx_message_outbox_aggregate (aggregate_type, aggregate_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_task_checkpoint (
    id BIGINT NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    checkpoint_key VARCHAR(80) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    retention_class VARCHAR(32) NOT NULL DEFAULT 'RECOVERY_CHECKPOINT',
    expires_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_task_checkpoint (task_id, checkpoint_key),
    KEY idx_trip_task_checkpoint_task (task_id, deleted),
    KEY idx_trip_task_checkpoint_expiry (retention_class, expires_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_memory_fact (
    id BIGINT NOT NULL, user_id BIGINT NOT NULL, memory_type VARCHAR(32) NOT NULL,
    memory_key VARCHAR(100) NOT NULL, memory_value_json LONGTEXT NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL', scope_value VARCHAR(128) NOT NULL DEFAULT '',
    source VARCHAR(16) NOT NULL, confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    hard_constraint TINYINT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL,
    memory_fingerprint VARCHAR(64) NOT NULL, first_seen_at DATETIME(6) NOT NULL,
    last_observed_at DATETIME(6) NOT NULL, last_confirmed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL, superseded_by BIGINT NULL, evidence_refs_json TEXT NULL,
    create_time DATETIME(6) NOT NULL, update_time DATETIME(6) NOT NULL, deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id), UNIQUE KEY uk_user_memory_fingerprint (user_id, memory_fingerprint),
    KEY idx_user_memory_active (user_id, status, expires_at, deleted),
    KEY idx_user_memory_type (user_id, memory_type, memory_key, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_memory_event (
    id BIGINT NOT NULL, event_id VARCHAR(160) NOT NULL, user_id BIGINT NOT NULL,
    plan_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, task_id VARCHAR(64) NULL,
    event_type VARCHAR(40) NOT NULL, target_type VARCHAR(40) NOT NULL DEFAULT '', target_ref VARCHAR(255) NOT NULL DEFAULT '',
    city VARCHAR(80) NOT NULL DEFAULT '', day_number INT NULL, before_json LONGTEXT NULL, after_json LONGTEXT NULL,
    reason_code VARCHAR(64) NOT NULL DEFAULT '', reason_text VARCHAR(1000) NOT NULL DEFAULT '',
    source VARCHAR(32) NOT NULL, evidence_refs_json TEXT NULL, occurred_at DATETIME(6) NOT NULL,
    consolidation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', processed_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL, update_time DATETIME(6) NOT NULL, deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id), UNIQUE KEY uk_trip_memory_event_id (event_id),
    KEY idx_trip_memory_event_user (user_id, occurred_at, deleted),
    KEY idx_trip_memory_event_plan (plan_id, plan_version, deleted),
    KEY idx_trip_memory_event_consolidation (user_id, consolidation_status, occurred_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_plan_version (
    id BIGINT NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    created_by BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    task_id VARCHAR(64) NULL,
    result_json LONGTEXT NOT NULL,
    snapshot_json LONGTEXT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_plan_version (plan_id, version),
    UNIQUE KEY uk_trip_plan_version_task (task_id),
    KEY idx_trip_plan_version_created (plan_id, deleted, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS retrieval_trace (
    id BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    query_text TEXT NOT NULL,
    filter_json TEXT NOT NULL,
    retrieval_mode VARCHAR(40) NOT NULL,
    candidates_json LONGTEXT NOT NULL,
    final_citations_json LONGTEXT NOT NULL,
    latency_ms BIGINT NOT NULL,
    adopted TINYINT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_retrieval_trace_id (trace_id),
    KEY idx_retrieval_trace_created (deleted, create_time),
    KEY idx_retrieval_trace_adopted (adopted, deleted, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_plan_record (
    id BIGINT NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    city VARCHAR(255) NOT NULL DEFAULT '',
    start_date VARCHAR(16) NOT NULL DEFAULT '',
    end_date VARCHAR(16) NOT NULL DEFAULT '',
    travel_days INT NOT NULL DEFAULT 0,
    overall_suggestions TEXT NULL,
    result_json LONGTEXT NOT NULL,
    snapshot_json LONGTEXT NULL,
    error_message TEXT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_plan_record_plan_id (plan_id),
    UNIQUE KEY uk_trip_plan_record_task_id (task_id),
    KEY idx_trip_plan_record_updated (status, deleted, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    email VARCHAR(160) NULL,
    avatar_url VARCHAR(500) NOT NULL DEFAULT '',
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    bio VARCHAR(500) NOT NULL DEFAULT '',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    UNIQUE KEY uk_sys_user_email (email),
    KEY idx_sys_user_role_status (role, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user_credential (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    password_updated_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_credential_user (user_id),
    KEY idx_sys_user_credential_updated (password_updated_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_session (
    id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_token (token_hash),
    KEY idx_auth_session_user_expiry (user_id, expires_at, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_plan (
    id BIGINT NOT NULL,
    public_id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    city VARCHAR(80) NOT NULL,
    city_code VARCHAR(20) NOT NULL DEFAULT '',
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    travel_days INT NOT NULL,
    budget_cents BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    version INT NOT NULL DEFAULT 1,
    detail_json LONGTEXT NULL,
    current_snapshot_json LONGTEXT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_plan_public_id (public_id),
    KEY idx_trip_plan_owner (owner_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS system_runtime_setting (
    id BIGINT NOT NULL,
    setting_key VARCHAR(100) NOT NULL,
    encrypted_value LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIGURED',
    last_error VARCHAR(500) NOT NULL DEFAULT '',
    last_validated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_runtime_setting_key (setting_key),
    KEY idx_system_runtime_setting_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_day (
    id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    trip_date DATE NOT NULL,
    title VARCHAR(160) NOT NULL DEFAULT '',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_day_plan_number (plan_id, day_number),
    KEY idx_trip_day_plan (plan_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_item (
    id BIGINT NOT NULL,
    day_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    poi_name VARCHAR(160) NOT NULL,
    address VARCHAR(300) NOT NULL DEFAULT '',
    longitude DECIMAL(10, 6) NOT NULL,
    latitude DECIMAL(10, 6) NOT NULL,
    start_time TIME NOT NULL,
    stay_minutes INT NOT NULL DEFAULT 60,
    category VARCHAR(64) NOT NULL DEFAULT '景点',
    note VARCHAR(800) NOT NULL DEFAULT '',
    photo_url VARCHAR(1000) NOT NULL DEFAULT '',
    cost_cents BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_item_day_order (day_id, sort_order),
    KEY idx_trip_item_day (day_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_member (
    id BIGINT NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trip_member_plan_user (plan_id, user_id),
    KEY idx_trip_member_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trip_comment (
    id BIGINT NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    target_type VARCHAR(32) NOT NULL DEFAULT 'PLAN',
    target_ref VARCHAR(128) NOT NULL DEFAULT '',
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    like_count INT NOT NULL DEFAULT 0,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_trip_comment_plan (plan_id, deleted, create_time),
    KEY idx_trip_comment_parent (parent_id, deleted),
    KEY idx_trip_comment_target (plan_id, target_type, target_ref, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL DEFAULT 0,
    actor_name VARCHAR(80) NOT NULL DEFAULT '系统',
    action VARCHAR(160) NOT NULL,
    detail VARCHAR(800) NOT NULL DEFAULT '',
    source VARCHAR(80) NOT NULL DEFAULT '',
    result VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_audit_log_created (deleted, create_time),
    KEY idx_audit_log_actor (actor_user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_source (
    id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    endpoint VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    document_count INT NOT NULL DEFAULT 0,
    last_sync_at DATETIME(6) NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_source_name (name),
    KEY idx_knowledge_source_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    external_id VARCHAR(128) NOT NULL DEFAULT '',
    title VARCHAR(300) NOT NULL,
    source_url VARCHAR(1000) NOT NULL DEFAULT '',
    content LONGTEXT NULL,
    content_hash VARCHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    published_at DATETIME(6) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_knowledge_document_source (source_id, status, deleted),
    KEY idx_knowledge_document_external (source_id, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    keywords VARCHAR(1000) NOT NULL DEFAULT '',
    vector_ref VARCHAR(255) NOT NULL DEFAULT '',
    metadata_json TEXT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_chunk_document_index (document_id, chunk_index),
    KEY idx_knowledge_chunk_document (document_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO sys_user
    (id, username, display_name, email, avatar_url, role, status, bio, create_time, update_time, deleted)
VALUES
    (1001, 'linyue', '林悦', 'linyue@voyagemind.local', '', 'SUPER_ADMIN', 'ACTIVE', '平台超级管理员', NOW(6), NOW(6), 0),
    (1002, 'chenmo', '陈默', 'chenmo@voyagemind.local', '', 'USER', 'ACTIVE', '目的地研究员', NOW(6), NOW(6), 0),
    (1003, 'zhouan', '周安', 'zhouan@voyagemind.local', '', 'USER', 'ACTIVE', '旅行内容创作者', NOW(6), NOW(6), 0);

UPDATE sys_user SET role = 'SUPER_ADMIN', email = 'linyue@voyagemind.local'
WHERE id = 1001 AND deleted = 0;
UPDATE sys_user SET role = 'USER', email = 'chenmo@voyagemind.local'
WHERE id = 1002 AND deleted = 0;
UPDATE sys_user SET role = 'USER', email = 'zhouan@voyagemind.local'
WHERE id = 1003 AND deleted = 0;

INSERT IGNORE INTO trip_plan
    (id, public_id, owner_id, title, city, city_code, start_date, end_date, travel_days, budget_cents, status, visibility, create_time, update_time, deleted)
VALUES
    (5001, 'demo-shanghai', 1002, '上海·经典风情', '上海', '021', '2026-08-18', '2026-08-21', 4, 258000, 'PLANNING', 'PRIVATE', NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_day
    (id, plan_id, day_number, trip_date, title, create_time, update_time, deleted)
VALUES
    (5101, 5001, 1, '2026-08-18', '外滩 · 豫园 · 南京路', NOW(6), NOW(6), 0),
    (5102, 5001, 2, '2026-08-19', '徐汇人文 · 武康路', NOW(6), NOW(6), 0),
    (5103, 5001, 3, '2026-08-20', '迪士尼乐园', NOW(6), NOW(6), 0),
    (5104, 5001, 4, '2026-08-21', '滨江漫步 · 返程', NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_item
    (id, day_id, sort_order, poi_name, address, longitude, latitude, start_time, stay_minutes, category, note, photo_url, cost_cents, create_time, update_time, deleted)
VALUES
    (5201, 5101, 1, '外滩', '中山东一路', 121.490317, 31.241701, '10:00:00', 90, '城市地标', '沿黄浦江步行，观察万国建筑群。', '', 0, NOW(6), NOW(6), 0),
    (5202, 5101, 2, '豫园', '黄浦区福佑路168号', 121.492194, 31.227201, '11:30:00', 100, '园林景观', '错峰游览古典园林与老字号街区。', '', 4000, NOW(6), NOW(6), 0),
    (5203, 5101, 3, '南京路步行街', '南京东路', 121.478769, 31.235349, '13:20:00', 120, '城市漫步', '午餐后从东向西步行，减少折返。', '', 8000, NOW(6), NOW(6), 0),
    (5204, 5101, 4, '新天地', '马当路245号', 121.475199, 31.219041, '15:30:00', 90, '历史街区', '石库门街区与当代城市生活。', '', 6000, NOW(6), NOW(6), 0),
    (5211, 5102, 1, '武康大楼', '淮海中路1850号', 121.438842, 31.207803, '09:30:00', 45, '历史建筑', '从武康大楼开始徐汇历史风貌区步行。', '', 0, NOW(6), NOW(6), 0),
    (5212, 5102, 2, '武康路', '徐汇区武康路', 121.436065, 31.210449, '10:30:00', 90, '城市漫步', '保留咖啡休息与街区观察时间。', '', 5000, NOW(6), NOW(6), 0),
    (5213, 5102, 3, '上海图书馆东馆', '浦东新区合欢路300号', 121.548205, 31.224775, '14:00:00', 120, '公共文化', '提前预约，注意跨区交通时间。', '', 0, NOW(6), NOW(6), 0),
    (5221, 5103, 1, '上海迪士尼乐园', '浦东新区川沙新镇', 121.667731, 31.143426, '08:30:00', 600, '主题乐园', '提前到达并按实时排队情况调整顺序。', '', 47500, NOW(6), NOW(6), 0),
    (5231, 5104, 1, '北外滩滨江绿地', '虹口区东大名路', 121.505150, 31.254455, '09:30:00', 90, '滨江景观', '返程前安排低强度滨江步行。', '', 0, NOW(6), NOW(6), 0);

INSERT IGNORE INTO knowledge_source
    (id, name, source_type, endpoint, status, document_count, last_sync_at, description, create_time, update_time, deleted)
VALUES
    (2001, '高德地图', 'AMAP', 'https://restapi.amap.com', 'READY', 18, NOW(6), '地点、路线、天气与营业信息', NOW(6), NOW(6), 0),
    (2003, '官方旅游资料', 'OFFICIAL', '', 'READY', 6, NOW(6), '旅游局与景点官方资料', NOW(6), NOW(6), 0),
    (2004, '维基导游开放旅行知识', 'WEB', 'https://zh.wikivoyage.org/w/api.php', 'READY', 0, NULL,
        '中文维基导游城市条目，使用 MediaWiki API 同步，CC BY-SA 4.0', NOW(6), NOW(6), 0);

INSERT IGNORE INTO knowledge_document
    (id, source_id, external_id, title, source_url, content, content_hash, status, visibility, published_at, create_time, update_time, deleted)
VALUES
    (3001, 2003, 'hk-official-peak', '太平山顶游览提示', 'https://www.discoverhongkong.com', '太平山顶是香港代表性观景地点。傍晚及日落前通常是热门到访时段，应为交通和排队预留弹性时间。山顶天气变化较快，出发前应复核实时天气与交通状态。', 'seed-hk-peak', 'KEYWORD_ONLY', 'PUBLIC', NOW(6), NOW(6), NOW(6), 0),
    (3003, 2001, 'amap-hk-central', '香港中环步行区域知识', 'https://restapi.amap.com', '中环街市、大馆与荷李活道在地理上适合组成连续步行路线。路线包含坡道，携带大件行李或行动不便的旅行者应缩短连续步行距离，并预留公共交通替代方案。', 'seed-hk-central', 'KEYWORD_ONLY', 'TEAM', NOW(6), NOW(6), NOW(6), 0),
    (3004, 2003, 'sh-official-classic', '上海经典城区游览提示', 'https://www.shanghai.gov.cn', '外滩、豫园、南京路步行街和新天地适合按滨江、老城、商业街区、石库门街区的顺序理解上海。外滩步道本身无需门票，白天适合观察万国建筑群；豫园及周边在节假日和午后客流较大；南京路应优先保留步行体验；新天地适合傍晚到访。景点内部开放、门票和预约规则可能调整，出发前应以各景点官方公告为准。', 'seed-sh-classic', 'KEYWORD_ONLY', 'PUBLIC', NOW(6), NOW(6), NOW(6), 0),
    (3006, 2003, 'sh-official-xuhui', '徐汇历史风貌与公共文化游览提示', 'https://www.shanghai.gov.cn', '武康大楼和武康路适合组成连续的历史风貌步行路线，应在不影响居民和道路通行的前提下拍摄，并给咖啡休息预留时间。上海图书馆东馆位于浦东，与武康路不在同一区域，跨区前应核对交通时间；入馆规则、开放时段及活动预约以图书馆官方公告为准。', 'seed-sh-xuhui', 'KEYWORD_ONLY', 'PUBLIC', NOW(6), NOW(6), NOW(6), 0),
    (3007, 2003, 'sh-official-disney', '上海迪士尼乐园游览提示', 'https://www.shanghaidisneyresort.com', '上海迪士尼乐园通常需要安排完整一天，应提前通过官方渠道购买有效门票并核对入园证件要求。入园后根据官方应用显示的实时等候时间调整项目顺序，优先处理最想体验的项目，并为巡游、演出、用餐和返程预留缓冲。营业时间、项目维护和预约规则会变化，出发前及当天均应查看官方公告。', 'seed-sh-disney', 'KEYWORD_ONLY', 'PUBLIC', NOW(6), NOW(6), NOW(6), 0),
    (3008, 2001, 'amap-sh-north-bund', '北外滩滨江步行区域知识', 'https://restapi.amap.com', '北外滩滨江绿地适合安排低强度滨江步行，可从不同角度观察外滩与陆家嘴天际线。返程日应控制停留时间，提前核对从滨江到车站或机场的真实道路交通；雨天、强风或高温天气应缩短户外停留，并以高德实时路线和现场开放信息为准。', 'seed-sh-north-bund', 'KEYWORD_ONLY', 'TEAM', NOW(6), NOW(6), NOW(6), 0);

INSERT IGNORE INTO knowledge_chunk
    (id, document_id, chunk_index, content, keywords, vector_ref, metadata_json, create_time, update_time, deleted)
VALUES
    (3101, 3001, 0, '太平山顶是香港代表性观景地点。傍晚及日落前通常是热门到访时段，应为交通和排队预留弹性时间。山顶天气变化较快，出发前应复核实时天气与交通状态。', '香港,太平山顶,日落,排队', '', '{}', NOW(6), NOW(6), 0),
    (3102, 3002, 0, '西九龙文化区适合把 M+ 博物馆、海滨长廊和日落观景组合在同一半天。真实旅行反馈建议避免把中环与西九龙高频往返，以减少过海交通时间。', '香港,西九龙,M+,日落', '', '{}', NOW(6), NOW(6), 0),
    (3103, 3003, 0, '中环街市、大馆与荷李活道在地理上适合组成连续步行路线。路线包含坡道，携带大件行李或行动不便的旅行者应缩短连续步行距离，并预留公共交通替代方案。', '香港,中环,大馆,步行', '', '{}', NOW(6), NOW(6), 0),
    (3104, 3004, 0, '外滩、豫园、南京路步行街和新天地适合按滨江、老城、商业街区、石库门街区的顺序理解上海。外滩步道本身无需门票，白天适合观察万国建筑群；豫园及周边在节假日和午后客流较大；南京路应优先保留步行体验；新天地适合傍晚到访。景点内部开放、门票和预约规则可能调整，出发前应以各景点官方公告为准。', '上海,外滩,豫园,南京路,新天地,预约', '', '{}', NOW(6), NOW(6), 0),
    (3105, 3005, 0, '真实游览反馈建议外滩从北向南或从南向北连续步行，不要在同一时段反复横穿中山东一路；拍摄陆家嘴天际线应避开正午强光。豫园周边餐饮和商业密集，需区分园林游览与街区逛吃时间。夏季应准备防晒和饮水，热门机位不要长时间占位，遇到大型活动或临时限流应服从现场安排。', '上海,外滩,豫园,拍照,避坑,夏季', '', '{}', NOW(6), NOW(6), 0),
    (3106, 3006, 0, '武康大楼和武康路适合组成连续的历史风貌步行路线，应在不影响居民和道路通行的前提下拍摄，并给咖啡休息预留时间。上海图书馆东馆位于浦东，与武康路不在同一区域，跨区前应核对交通时间；入馆规则、开放时段及活动预约以图书馆官方公告为准。', '上海,武康大楼,武康路,上海图书馆东馆,预约', '', '{}', NOW(6), NOW(6), 0),
    (3107, 3007, 0, '上海迪士尼乐园通常需要安排完整一天，应提前通过官方渠道购买有效门票并核对入园证件要求。入园后根据官方应用显示的实时等候时间调整项目顺序，优先处理最想体验的项目，并为巡游、演出、用餐和返程预留缓冲。营业时间、项目维护和预约规则会变化，出发前及当天均应查看官方公告。', '上海,迪士尼,门票,排队,预约', '', '{}', NOW(6), NOW(6), 0),
    (3108, 3008, 0, '北外滩滨江绿地适合安排低强度滨江步行，可从不同角度观察外滩与陆家嘴天际线。返程日应控制停留时间，提前核对从滨江到车站或机场的真实道路交通；雨天、强风或高温天气应缩短户外停留，并以高德实时路线和现场开放信息为准。', '上海,北外滩,滨江,返程,高德路线', '', '{}', NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_member
    (id, plan_id, user_id, member_role, create_time, update_time, deleted)
VALUES
    (3001, 'demo-hong-kong', 1001, 'OWNER', NOW(6), NOW(6), 0),
    (3002, 'demo-hong-kong', 1002, 'EDITOR', NOW(6), NOW(6), 0),
    (3003, 'demo-hong-kong', 1003, 'VIEWER', NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_member
    (id, plan_id, user_id, member_role, create_time, update_time, deleted)
VALUES
    (3011, 'demo-shanghai', 1002, 'OWNER', NOW(6), NOW(6), 0),
    (3012, 'demo-shanghai', 1001, 'EDITOR', NOW(6), NOW(6), 0),
    (3013, 'demo-shanghai', 1003, 'EDITOR', NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_comment
    (id, plan_id, user_id, parent_id, target_type, target_ref, content, status, like_count, create_time, update_time, deleted)
VALUES
    (4001, 'demo-hong-kong', 1001, NULL, 'POI', '星光大道', '建议在星光大道多留 20 分钟，日落时段视野更好。', 'OPEN', 2, NOW(6), NOW(6), 0),
    (4002, 'demo-hong-kong', 1002, 4001, 'POI', '星光大道', '已检查当天日落时间，可以把天星小轮顺延到 18:20。', 'OPEN', 1, NOW(6), NOW(6), 0),
    (4003, 'demo-hong-kong', 1003, NULL, 'ROUTE', '星光大道-天星小轮', '这段步行距离合适，保留沿海路线。', 'RESOLVED', 3, NOW(6), NOW(6), 0);

INSERT IGNORE INTO trip_comment
    (id, plan_id, user_id, parent_id, target_type, target_ref, content, status, like_count, create_time, update_time, deleted)
VALUES
    (4011, 'demo-shanghai', 1001, NULL, 'POI', '豫园', '建议上午先到豫园，11 点后团队客流会明显增加。', 'OPEN', 2, NOW(6), NOW(6), 0),
    (4012, 'demo-shanghai', 1003, NULL, 'ROUTE', 'day-1', '南京路到新天地建议乘地铁，步行会压缩下午游览时间。', 'OPEN', 1, NOW(6), NOW(6), 0);
