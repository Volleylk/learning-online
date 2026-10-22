package com.xuecheng.media.model.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @description 发起分片上传（init）返回结果
 */
@Data
public class InitMultipartResultDto {

    /**
     * MinIO multipart 会话ID（fileExists=true 时为 null，无需上传）
     */
    @ApiModelProperty("上传会话ID")
    private String uploadId;

    /**
     * 文件是否已存在（秒传命中）
     */
    @ApiModelProperty("秒传命中标记")
    private boolean fileExists;

    /**
     * 秒传命中时的文件ID
     */
    @ApiModelProperty("已存在文件ID")
    private String fileId;

}
