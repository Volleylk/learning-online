-- ============================================================
-- 学成在线 media 模块：讲义解析状态表
-- 数据库：xcplus_media
-- 说明：讲义上传后由 media 服务写 processing，agent 消费解析完成后
--       回调 media 接口更新为 success / failed；教学机构端轮询查询。
-- ============================================================

USE `xcplus_media`;

CREATE TABLE IF NOT EXISTS `lecture_parse_status` (
    `file_id`     VARCHAR(64)   NOT NULL COMMENT '文件ID（上传时服务端生成 UUID，主键）',
    `course_id`   VARCHAR(20)   NULL     COMMENT '课程ID',
    `course_name` VARCHAR(255)  NULL     COMMENT '课程名称',
    `source_url`  VARCHAR(500)  NULL     COMMENT 'MinIO 访问 URL',
    `status`      VARCHAR(16)   NOT NULL DEFAULT 'processing' COMMENT '解析状态：processing/success/failed',
    `message`     VARCHAR(500)  NULL     COMMENT '状态说明（失败原因/切片数）',
    `create_time` DATETIME      NULL     COMMENT '创建时间',
    `update_time` DATETIME      NULL     COMMENT '更新时间',
    PRIMARY KEY (`file_id`),
    KEY `idx_course` (`course_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '讲义解析状态';
