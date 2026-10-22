package com.xuecheng.media.service;

import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.model.dto.InitMultipartResultDto;
import com.xuecheng.media.model.dto.UploadedPartDto;

import java.io.InputStream;
import java.util.List;

/**
 * @description 大文件分片上传（S3 Multipart 标准实现）
 */
public interface MultipartUploadService {

    /**
     * 发起上传：秒传检查 -> 建 MinIO multipart 会话 -> 持久化会话
     * 幂等：同一 fileMd5 存在未完成会话则复用 uploadId
     */
    RestResponse<InitMultipartResultDto> initMultipart(Long companyId, String fileMd5, String fileName,
                                                       int chunkTotal, long totalSize);

    /**
     * 上传一个分片（流式直传），返回该片 ETag
     */
    RestResponse<UploadedPartDto> uploadPart(String uploadId, int partNumber, InputStream in, long partSize);

    /**
     * 列出会话已上传的分片（断点续传判定）
     */
    RestResponse<List<UploadedPartDto>> listParts(String uploadId);

    /**
     * 合并：服务端收齐分片 -> 校验连续 -> 原子合并 -> size 校验 -> 入库
     */
    RestResponse<Boolean> completeMultipart(String uploadId, String fileMd5, Long companyId);

    /**
     * 放弃上传：清理 MinIO 会话与数据库记录
     */
    RestResponse<Boolean> abortMultipart(String uploadId);

}
