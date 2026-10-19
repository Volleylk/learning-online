package com.xuecheng.media.api;

import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.service.MediaFileService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

/**
 * @author Mr.M
 * @version 1.0
 * &#064;description  媒资文件管理接口
 */
@Api(value = "媒资文件管理接口", tags = "媒资文件管理接口")
@RestController
@RequiredArgsConstructor
public class MediaFilesController {


    private final MediaFileService mediaFileService;

    @ApiOperation("媒资文件分页查询")
    @PostMapping("/files")
    public PageResult<MediaFiles> queryMediaList(@ModelAttribute PageParams pageParams, @RequestBody QueryMediaParamsDto queryMediaParams) {
        Long companyId = 1232141425L;
        PageResult<MediaFiles> pageResult = mediaFileService.queryMediaFiels(companyId,pageParams, queryMediaParams);
        return pageResult;
    }

    @ApiOperation("上传文件")
    @RequestMapping(value = "/upload/coursefile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public UploadFileResultDto upload(@RequestPart("filedata") MultipartFile filedata, @RequestParam(value = "folder", required = false) String folder, @RequestParam(value = "objectName", required = false) String objectName) throws IOException {

        Long companyId = 1232141425L;
        UploadFileParamsDto uploadFileParamsDto = new UploadFileParamsDto();
        //文件大小
        uploadFileParamsDto.setFileSize(filedata.getSize());
        //图片
        uploadFileParamsDto.setFileType("001001");
        //文件名称
        uploadFileParamsDto.setFilename(filedata.getOriginalFilename());
        //创建临时文件
        File tempFile = null;
        try {
            tempFile = File.createTempFile("minio", "temp");
            //上传的文件拷贝到临时文件
            filedata.transferTo(tempFile);
            //文件路径
            String absolutePath = tempFile.getAbsolutePath();
            //上传文件
            UploadFileResultDto uploadFileResultDto = mediaFileService.uploadFile(companyId, uploadFileParamsDto, absolutePath, objectName);
            return uploadFileResultDto;
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

}
