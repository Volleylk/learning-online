package com.xuecheng.media.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 讲义解析状态（持久化到 MySQL，media 重启不丢失）
 * </p>
 *
 * @author xuecheng-plus
 */
@Data
@TableName("lecture_parse_status")
public class LectureParseStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件ID（消息中的 fileId，上传时由服务端生成 UUID）
     */
    @TableId(value = "file_id", type = IdType.INPUT)
    private String fileId;

    /**
     * 课程ID
     */
    private String courseId;

    /**
     * 课程名称
     */
    private String courseName;

    /**
     * MinIO 访问 URL
     */
    private String sourceUrl;

    /**
     * 解析状态：processing / success / failed
     */
    private String status;

    /**
     * 状态说明
     */
    private String message;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
