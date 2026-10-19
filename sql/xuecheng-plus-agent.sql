-- ============================================================
-- 学成在线 AI 助手（xuecheng-plus-agent）建表脚本
-- 数据库：xcplus_agent（UTF8MB4）
-- 说明：agent 启动时也会通过 SQLAlchemy create_all 自动建表（IF NOT EXISTS 语义），
--       本脚本是表结构的权威定义，供审阅 / 部署 / 迁移使用。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `xcplus_agent`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `xcplus_agent`;

-- ------------------------------------------------------------
-- 聊天记录（短期对话记忆 + 审计；"历史会话"由本表按 session_id 聚合，无独立会话表）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_history` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `session_id` VARCHAR(64)  NOT NULL COMMENT '会话ID（前端生成 UUID）',
    `role`       VARCHAR(16)  NOT NULL COMMENT '角色：user / assistant',
    `content`    TEXT         NOT NULL COMMENT '消息内容',
    `created_at` DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_session` (`session_id`),
    KEY `idx_user_session_time` (`user_id`, `session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AI 助手聊天记录';

-- ------------------------------------------------------------
-- 用户长期偏好（按用户唯一）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `user_id`           VARCHAR(64)  NOT NULL COMMENT '用户ID（主键）',
    `learning_stage`    VARCHAR(64)  NULL     COMMENT '学习阶段：入门/进阶/求职',
    `explanation_style` VARCHAR(64)  NULL     COMMENT '解释风格：通俗/严谨/举例',
    `focus_directions`  TEXT         NULL     COMMENT '关注方向（逗号分隔）',
    `extra`             TEXT         NULL     COMMENT '扩展字段 JSON',
    `updated_at`        DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AI 助手用户长期偏好';
