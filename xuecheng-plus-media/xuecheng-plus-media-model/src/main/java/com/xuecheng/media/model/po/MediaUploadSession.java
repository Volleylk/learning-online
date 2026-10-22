package com.xuecheng.media.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 大文件分片上传会话（S3 Multipart Upload 的 uploadId 状态持久化）
 * </p>
 *
 * @author itcast
 */
@Data
@TableName("media_upload_session")
public class MediaUploadSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 文件MD5（唯一，一个文件一个会话）
     */
    private String fileMd5;

    /**
     * MinIO multipart 会话ID
     */
    private String uploadId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 总分片数
     */
    private Integer chunkTotal;

    /**
     * 文件总大小
     */
    private Long totalSize;

    /**
     * 状态：0上传中 1已完成 2已放弃
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
