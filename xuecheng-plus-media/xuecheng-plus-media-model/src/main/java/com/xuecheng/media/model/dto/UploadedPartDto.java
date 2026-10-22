package com.xuecheng.media.model.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @description 已上传分片信息（partNumber + etag，用于完整性与顺序校验）
 */
@Data
public class UploadedPartDto {

    /**
     * 分片序号（从1开始）
     */
    @ApiModelProperty("分片序号")
    private int partNumber;

    /**
     * 分片ETag（MinIO 返回，逐片校验）
     */
    @ApiModelProperty("分片ETag")
    private String etag;

}
