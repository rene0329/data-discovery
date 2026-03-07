-- 训练镜像路由配置：方案B（按场景拆镜像）

CREATE TABLE IF NOT EXISTS training_profile (
    id INT AUTO_INCREMENT PRIMARY KEY,
    profile_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL COMMENT 'recsys/text/image',
    model_type VARCHAR(64) NOT NULL COMMENT 'bpr/gru/resnet18',
    image VARCHAR(255) NOT NULL,
    entrypoint VARCHAR(255) NOT NULL DEFAULT '/app/train.py',
    data_path_template VARCHAR(255) NOT NULL DEFAULT '/data/{dataset}',
    default_cpu DOUBLE NULL,
    default_mem DOUBLE NULL COMMENT 'Gi',
    active TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_training_profile_profile_id (profile_id),
    INDEX idx_training_profile_task_type (task_type),
    INDEX idx_training_profile_active (active)
);

CREATE TABLE IF NOT EXISTS dataset_profile_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_name VARCHAR(255) NOT NULL,
    profile_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dataset_profile_binding_name (dataset_name),
    INDEX idx_dataset_profile_binding_profile (profile_id)
);

-- 默认配置（请替换为你的实际镜像地址）
INSERT INTO training_profile (profile_id, task_type, model_type, image, entrypoint, data_path_template, default_cpu, default_mem, active)
SELECT * FROM (
    SELECT 'recsys_bpr', 'recsys', 'bpr', 'your-registry/trainer-recsys-bpr:latest', '/app/train.py', '/data/recsys/{dataset}', 0.5, 1.0, 1
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM training_profile WHERE profile_id = 'recsys_bpr');

INSERT INTO training_profile (profile_id, task_type, model_type, image, entrypoint, data_path_template, default_cpu, default_mem, active)
SELECT * FROM (
    SELECT 'text_gru_zh', 'text', 'gru', 'your-registry/trainer-text-zh-gru:latest', '/app/train.py', '/data/text/{dataset}', 0.5, 1.0, 1
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM training_profile WHERE profile_id = 'text_gru_zh');

INSERT INTO training_profile (profile_id, task_type, model_type, image, entrypoint, data_path_template, default_cpu, default_mem, active)
SELECT * FROM (
    SELECT 'image_resnet18', 'image', 'resnet18', 'your-registry/trainer-image-resnet18:latest', '/app/train.py', '/data/image/{dataset}', 1.0, 2.0, 1
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM training_profile WHERE profile_id = 'image_resnet18');

-- 示例绑定（按你的真实 dataset_name 调整）
-- INSERT INTO dataset_profile_binding(dataset_name, profile_id)
-- VALUES ('ratings.txt', 'recsys_bpr')
-- ON DUPLICATE KEY UPDATE profile_id = VALUES(profile_id);
