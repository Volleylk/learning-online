package com.xuecheng.media.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListPartsRequest;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PartListing;
import com.amazonaws.services.s3.model.PartSummary;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaUploadSessionMapper;
import com.xuecheng.media.model.dto.InitMultipartResultDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadedPartDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaUploadSession;
import com.xuecheng.media.service.MediaFileService;
import com.xuecheng.media.service.MultipartUploadService;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code @description} 大文件分片上传（S3 Multipart 标准实现）
 * <p>
 * 流程：init(建会话) -> part×N(传分片) -> complete(收齐合并)
 * 断点续传：listParts 查已传分片，只补缺失的片
 * <p>
 * 说明：minio-java 8.4+ 移除了公开的 Multipart API（自动分片内部化），
 * 而 MinIO 完全兼容 S3 协议，故本链路使用 AWS SDK(AmazonS3) 调标准 S3 Multipart，
 * 得到真实的 uploadId 与逐片 ETag。普通上传/查询仍走 minio-java。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultipartUploadServiceImpl implements MultipartUploadService {

    private final MediaUploadSessionMapper sessionMapper;

    private final MediaFilesMapper mediaFilesMapper;

    private final MediaFileService mediaFileService;

    private final MinioClient minioClient;

    /** AWS S3 客户端：MinIO 兼容 S3 API，负责 Multipart 标准协议 */
    private final AmazonS3 amazonS3;

    //视频文件桶
    @Value("${minio.bucket.videofiles}")
    private String bucketVideo;

    //会话状态：0上传中 1已完成 2已放弃
    private static final int STATUS_UPLOADING = 0;
    private static final int STATUS_COMPLETED = 1;
    private static final int STATUS_ABORTED = 2;

    @Override
    @Transactional
    public RestResponse<InitMultipartResultDto> initMultipart(Long companyId, String fileMd5, String fileName,
                                                              int chunkTotal, long totalSize) {
        InitMultipartResultDto result = new InitMultipartResultDto();

        //① 秒传：media_files 已有该 md5 的文件，直接返回，无需建会话
        MediaFiles existFile = mediaFilesMapper.selectById(fileMd5);
        if (existFile != null) {
            result.setFileExists(true);
            result.setFileId(existFile.getId());
            return RestResponse.success(result);
        }

        //② 会话幂等：同一文件已有未完成会话 -> 复用 uploadId（前端断点重传不会重复建会话）
        MediaUploadSession existSession = sessionMapper.selectOne(
                new LambdaQueryWrapper<MediaUploadSession>()
                        .eq(MediaUploadSession::getFileMd5, fileMd5));
        if (existSession != null && existSession.getStatus() == STATUS_UPLOADING) {
            result.setUploadId(existSession.getUploadId());
            return RestResponse.success(result);
        }

        //③ 新建 multipart 会话：对象名在 init 时定死（S3 要求），与合并路径一致
        String objectName = buildObjectName(fileMd5, getExtension(fileName));
        InitiateMultipartUploadResult upload;
        try {
            upload = amazonS3.initiateMultipartUpload(
                    new InitiateMultipartUploadRequest(bucketVideo, objectName));
        } catch (Exception e) {
            log.error("创建分片上传会话失败,fileMd5:{},异常:{}", fileMd5, e.getMessage(), e);
            return RestResponse.validfail(null, "创建分片上传会话失败。");
        }

        MediaUploadSession session = new MediaUploadSession();
        session.setFileMd5(fileMd5);
        session.setUploadId(upload.getUploadId());
        session.setFileName(fileName);
        session.setChunkTotal(chunkTotal);
        session.setTotalSize(totalSize);
        session.setStatus(STATUS_UPLOADING);
        session.setCreateTime(LocalDateTime.now());
        sessionMapper.insert(session);

        result.setUploadId(upload.getUploadId());
        return RestResponse.success(result);
    }

    @Override
    public RestResponse<UploadedPartDto> uploadPart(String uploadId, int partNumber, InputStream in, long partSize) {
        MediaUploadSession session = getSessionByUploadId(uploadId);
        if (session == null || session.getStatus() != STATUS_UPLOADING) {
            return RestResponse.validfail(null, "上传会话不存在或已结束。");
        }
        if (partNumber < 1 || partNumber > session.getChunkTotal()) {
            return RestResponse.validfail(null, "分片序号超出范围。");
        }
        UploadPartResult result;
        try {
            UploadPartRequest request = new UploadPartRequest()
                    .withBucketName(bucketVideo)
                    .withKey(buildObjectName(session.getFileMd5(), getExtension(session.getFileName())))
                    .withUploadId(uploadId)
                    .withPartNumber(partNumber)
                    .withInputStream(in)
                    .withPartSize(partSize);
            result = amazonS3.uploadPart(request);
        } catch (Exception e) {
            log.error("上传分片失败,uploadId:{},partNumber:{},异常:{}", uploadId, partNumber, e.getMessage(), e);
            return RestResponse.validfail(null, "上传分片失败。");
        }
        UploadedPartDto dto = new UploadedPartDto();
        dto.setPartNumber(partNumber);
        //S3 每片返回 ETag，complete 时服务端据此做逐片内容校验
        dto.setEtag(result.getPartETag().getETag());
        return RestResponse.success(dto);
    }

    @Override
    public RestResponse<List<UploadedPartDto>> listParts(String uploadId) {
        MediaUploadSession session = getSessionByUploadId(uploadId);
        if (session == null) {
            return RestResponse.validfail(null, "上传会话不存在。");
        }
        List<UploadedPartDto> list = new ArrayList<>();
        try {
            collectParts(uploadId, buildObjectName(session.getFileMd5(), getExtension(session.getFileName())),
                    ps -> {
                        UploadedPartDto dto = new UploadedPartDto();
                        dto.setPartNumber(ps.getPartNumber());
                        dto.setEtag(ps.getETag());
                        list.add(dto);
                    });
        } catch (Exception e) {
            log.error("查询已传分片失败,uploadId:{},异常:{}", uploadId, e.getMessage(), e);
            return RestResponse.validfail(null, "查询已传分片失败。");
        }
        return RestResponse.success(list);
    }

    @Override
    @Transactional
    public RestResponse<Boolean> completeMultipart(String uploadId, String fileMd5, Long companyId) {
        MediaUploadSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<MediaUploadSession>()
                        .eq(MediaUploadSession::getFileMd5, fileMd5));
        if (session == null) {
            return RestResponse.validfail(false, "上传会话不存在。");
        }
        if (session.getStatus() == STATUS_COMPLETED) {
            //幂等：已完成直接返回成功（complete 被重复调用不重复合并）
            return RestResponse.success(true);
        }
        String objectName = buildObjectName(fileMd5, getExtension(session.getFileName()));

        //① 服务端自动收齐分片，校验连续 1..chunkTotal（ListParts 可能分页，循环收齐）
        List<PartETag> partETags = collectAndCheckParts(uploadId, objectName, session.getChunkTotal());
        if (partETags == null) {
            return RestResponse.validfail(false, "分片不完整，无法合并。");
        }

        //② 原子合并：S3 服务端用 ETag 列表校验每片后一次性合成
        try {
            amazonS3.completeMultipartUpload(
                    new CompleteMultipartUploadRequest(bucketVideo, objectName, uploadId, partETags));
            log.debug("合并文件成功:{}", objectName);
        } catch (Exception e) {
            log.error("合并文件失败,fileMd5:{},异常:{}", fileMd5, e.getMessage(), e);
            return RestResponse.validfail(false, "合并文件失败。");
        }

        //③ 用 statObject 校验合并后文件大小（替代下载整文件算 MD5，避免大文件全量回传）
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketVideo)
                    .object(objectName)
                    .build());
            if (stat.size() != session.getTotalSize()) {
                log.error("合并文件大小校验失败,fileMd5:{},期望:{},实际:{}", fileMd5, session.getTotalSize(), stat.size());
                return RestResponse.validfail(false, "文件合并校验失败，最终上传失败。");
            }
        } catch (Exception e) {
            log.error("校验合并文件失败,fileMd5:{},异常:{}", fileMd5, e.getMessage(), e);
            return RestResponse.validfail(false, "合并文件校验失败。");
        }

        //④ 入库（注入代理接口调用，事务正常生效，无需 applicationContext.getBean）
        UploadFileParamsDto params = new UploadFileParamsDto();
        params.setFilename(session.getFileName());
        params.setFileType("001002");
        params.setTags("课程视频");
        params.setRemark("");
        params.setFileSize(session.getTotalSize());
        mediaFileService.addMediaFilesToDb(companyId, fileMd5, params, bucketVideo, objectName);

        //⑤ 会话标记完成
        MediaUploadSession update = new MediaUploadSession();
        update.setStatus(STATUS_COMPLETED);
        sessionMapper.update(update, new LambdaQueryWrapper<MediaUploadSession>()
                .eq(MediaUploadSession::getId, session.getId()));
        return RestResponse.success(true);
    }

    @Override
    public RestResponse<Boolean> abortMultipart(String uploadId) {
        MediaUploadSession session = getSessionByUploadId(uploadId);
        if (session == null) {
            return RestResponse.success(true);
        }
        try {
            //S3 服务端回收隐藏分片，不留残留对象
            amazonS3.abortMultipartUpload(new AbortMultipartUploadRequest(bucketVideo,
                    buildObjectName(session.getFileMd5(), getExtension(session.getFileName())), uploadId));
        } catch (Exception e) {
            log.warn("放弃上传会话失败,uploadId:{},异常:{}", uploadId, e.getMessage());
        }
        sessionMapper.delete(new LambdaQueryWrapper<MediaUploadSession>()
                .eq(MediaUploadSession::getId, session.getId()));
        return RestResponse.success(true);
    }

    /**
     * 收齐分片并校验：数量 == chunkTotal，且 partNumber 连续 1..chunkTotal（S3 合并要求）
     * ListParts 单次最多返回 1000 片，需按 NextPartNumberMarker 分页循环
     */
    private List<PartETag> collectAndCheckParts(String uploadId, String objectName, int chunkTotal) {
        List<PartETag> parts = new ArrayList<>();
        try {
            collectParts(uploadId, objectName, ps -> parts.add(new PartETag(ps.getPartNumber(), ps.getETag())));
        } catch (Exception e) {
            log.error("收集分片失败,uploadId:{},异常:{}", uploadId, e.getMessage(), e);
            return null;
        }
        if (parts.size() != chunkTotal) {
            log.error("分片数量不完整,uploadId:{},期望:{},实际:{}", uploadId, chunkTotal, parts.size());
            return null;
        }
        //按 partNumber 升序（S3 要求），并校验连续
        parts.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).getPartNumber() != i + 1) {
                log.error("分片序号不连续,uploadId:{}", uploadId);
                return null;
            }
        }
        return parts;
    }

    /**
     * 分页拉取全部已传分片，逐个交给 consumer（PartSummary -> 业务对象）
     */
    private void collectParts(String uploadId, String objectName,
                              java.util.function.Consumer<PartSummary> consumer) throws Exception {
        ListPartsRequest request = new ListPartsRequest(bucketVideo, objectName, uploadId);
        PartListing listing;
        do {
            listing = amazonS3.listParts(request);
            for (PartSummary ps : listing.getParts()) {
                consumer.accept(ps);
            }
            if (listing.isTruncated()) {
                request.setPartNumberMarker(listing.getNextPartNumberMarker());
            }
        } while (listing.isTruncated());
    }

    private MediaUploadSession getSessionByUploadId(String uploadId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<MediaUploadSession>()
                .eq(MediaUploadSession::getUploadId, uploadId));
    }

    /**
     * 合并对象名：md5[0]/md5[1]/md5/md5.扩展名（与 MediaFileServiceImpl.getFilePathByMd5 保持一致）
     */
    private String buildObjectName(String fileMd5, String extName) {
        return fileMd5.charAt(0) + "/" + fileMd5.charAt(1) + "/" + fileMd5 + "/" + fileMd5 + extName;
    }

    private String getExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int idx = fileName.lastIndexOf(".");
        return idx >= 0 ? fileName.substring(idx) : "";
    }
}
