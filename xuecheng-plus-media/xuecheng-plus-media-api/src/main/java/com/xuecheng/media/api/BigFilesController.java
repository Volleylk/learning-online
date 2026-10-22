package com.xuecheng.media.api;

import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.model.dto.InitMultipartResultDto;
import com.xuecheng.media.model.dto.UploadedPartDto;
import com.xuecheng.media.service.MultipartUploadService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * @author Mr.M
 * @version 1.0
 * @description 大文件上传接口
 */
@Api(value = "大文件上传接口", tags = "大文件上传接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/upload")
public class BigFilesController {

    private final MultipartUploadService multipartUploadService;

    /* =============== S3 Multipart 标准实现 =============== */

    @ApiOperation(value = "发起分片上传（秒传检查+建会话）")
    @PostMapping("/multipart/init")
    public RestResponse<InitMultipartResultDto> initMultipart(@RequestParam("fileMd5") String fileMd5,
                                                              @RequestParam("fileName") String fileName,
                                                              @RequestParam("chunkTotal") int chunkTotal,
                                                              @RequestParam("totalSize") long totalSize) {
        Long companyId = 1232141425L;
        return multipartUploadService.initMultipart(companyId, fileMd5, fileName, chunkTotal, totalSize);
    }

    @ApiOperation(value = "上传分片（流式直传，返回ETag）")
    @PostMapping("/multipart/part")
    public RestResponse<UploadedPartDto> uploadPart(@RequestParam("file") MultipartFile file,
                                                    @RequestParam("uploadId") String uploadId,
                                                    @RequestParam("partNumber") int partNumber) throws IOException {
        return multipartUploadService.uploadPart(uploadId, partNumber, file.getInputStream(), file.getSize());
    }

    @ApiOperation(value = "查询已传分片列表（断点续传判定）")
    @PostMapping("/multipart/parts")
    public RestResponse<List<UploadedPartDto>> listParts(@RequestParam("uploadId") String uploadId) {
        return multipartUploadService.listParts(uploadId);
    }

    @ApiOperation(value = "合并分片（服务端收齐+校验+原子合并+入库）")
    @PostMapping("/multipart/complete")
    public RestResponse<Boolean> completeMultipart(@RequestParam("uploadId") String uploadId,
                                                   @RequestParam("fileMd5") String fileMd5) {
        Long companyId = 1232141425L;
        return multipartUploadService.completeMultipart(uploadId, fileMd5, companyId);
    }

    @ApiOperation(value = "放弃上传（清理会话）")
    @PostMapping("/multipart/abort")
    public RestResponse<Boolean> abortMultipart(@RequestParam("uploadId") String uploadId) {
        return multipartUploadService.abortMultipart(uploadId);
    }

}