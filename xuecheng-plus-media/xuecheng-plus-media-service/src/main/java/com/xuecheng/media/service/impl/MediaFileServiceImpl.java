package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static java.io.File.createTempFile;

/**
 * {@code @description} 媒资文件管理业务类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MediaFileServiceImpl implements MediaFileService {


    private final MediaFilesMapper mediaFilesMapper;

    private final MinioClient minioClient;

    private final ApplicationContext applicationContext;

    private final MediaProcessMapper mediaProcessMapper;


    //普通文件桶
    @Value("${minio.bucket.files}")
    private String bucketFiles;

    //视频文件桶
    @Value("${minio.bucket.videofiles}")
    private String bucketVideo;


    @Override
    public PageResult<MediaFiles> queryMediaFiels(Long companyId, PageParams pageParams, QueryMediaParamsDto queryMediaParamsDto) {

        //构建查询条件对象
        LambdaQueryWrapper<MediaFiles> queryWrapper = new LambdaQueryWrapper<>();
        //机构隔离：只看本机构的文件
        queryWrapper.eq(companyId != null, MediaFiles::getCompanyId, companyId);
        //可选过滤：前端传了才拼条件（第一个参数为false则不拼）
        queryWrapper.like(StringUtils.hasText(queryMediaParamsDto.getFilename()),
                MediaFiles::getFilename, queryMediaParamsDto.getFilename());
        queryWrapper.eq(StringUtils.hasText(queryMediaParamsDto.getFileType()),
                MediaFiles::getFileType, queryMediaParamsDto.getFileType());
        queryWrapper.eq(StringUtils.hasText(queryMediaParamsDto.getAuditStatus()),
                MediaFiles::getAuditStatus, queryMediaParamsDto.getAuditStatus());

        //分页对象
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());
        // 查询数据内容获得结果
        Page<MediaFiles> pageResult = mediaFilesMapper.selectPage(page, queryWrapper);
        // 获取数据列表
        List<MediaFiles> list = pageResult.getRecords();
        // 获取数据总数
        long total = pageResult.getTotal();
        // 构建结果集
        return new PageResult<>(list, total, pageParams.getPageNo(), pageParams.getPageSize());

    }

    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, String localFilePath, String objectName) {
        //判断文件是否存在
        File file = new File(localFilePath);
        if (!file.exists()) {
            XueChengPlusException.cast("文件不存在");
        }
        //文件的md5值
        String fileMd5 = getFileMd5(file);
        //判断是否已经上传过
        MediaFiles existFile = mediaFilesMapper.selectById(fileMd5);
        if (existFile != null) {
            UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
            BeanUtils.copyProperties(existFile, uploadFileResultDto);
            return uploadFileResultDto;
        }
        //文件名称
        String filename = uploadFileParamsDto.getFilename();
        //文件扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        //文件mimeType
        String mimeType = getMimeType(extension);
        //存储到minio中的对象名(带目录)：调用方指定objectName则用之，否则按 年/月/日/md5 生成
        if (objectName == null || objectName.trim().isEmpty()) {
            String defaultFolderPath = getDefaultFolderPath();
            objectName = defaultFolderPath + fileMd5 + extension;
        }
        //将文件上传到minio
        boolean b = addMediaFilesToMinIO(localFilePath, mimeType, bucketFiles, objectName);
        //文件大小
        uploadFileParamsDto.setFileSize(file.length());
        //将文件信息存储到数据库
        //从容器获取自己的代理对象
        MediaFileService proxy = applicationContext.getBean(MediaFileService.class);
        MediaFiles mediaFiles = proxy.addMediaFilesToDb(companyId, fileMd5, uploadFileParamsDto, bucketFiles, objectName);
        //准备返回数据
        UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
        BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);
        return uploadFileResultDto;
    }

    /**
     * 上传文件（流式直传）：Controller 不再落本地临时文件，输入流直接传 MinIO。
     * 两遍读流：① 第一遍算 MD5（秒传判定依赖 md5）；② 第二遍 putObject 流式直传（带准确 size，SDK 自动分片）。
     * 注意：必须传 MultipartFile 而不是 InputStream——InputStream 一次性消费，MD5 读完后 putObject 会 unexpected EOF。
     */
    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, MultipartFile file, long fileSize, String objectName) {
        //① 第一遍读流算 MD5（MultipartFile.getInputStream() 每次返回新流）
        String fileMd5;
        try {
            fileMd5 = getFileMd5(file.getInputStream());
        } catch (IOException e) {
            log.error("读取文件流失败", e);
            XueChengPlusException.cast("读取文件失败");
            return null;
        }
        if (fileMd5 == null) {
            XueChengPlusException.cast("读取文件失败");
        }
        //② 秒传：已上传过直接返回
        MediaFiles existFile = mediaFilesMapper.selectById(fileMd5);
        if (existFile != null) {
            UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
            BeanUtils.copyProperties(existFile, uploadFileResultDto);
            return uploadFileResultDto;
        }
        //文件名称
        String filename = uploadFileParamsDto.getFilename();
        //文件扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        //文件mimeType
        String mimeType = getMimeType(extension);
        //存储到minio中的对象名(带目录)：调用方指定objectName则用之，否则按 年/月/日/md5 生成
        if (objectName == null || objectName.trim().isEmpty()) {
            String defaultFolderPath = getDefaultFolderPath();
            objectName = defaultFolderPath + fileMd5 + extension;
        }
        //③ 第二遍流式直传 MinIO（重新获取输入流，不落盘）
        try {
            addMediaFilesToMinIOStream(file.getInputStream(), fileSize, mimeType, bucketFiles, objectName);
        } catch (IOException e) {
            log.error("读取文件流失败", e);
            XueChengPlusException.cast("读取文件失败");
        }
        //文件大小
        uploadFileParamsDto.setFileSize(fileSize);
        //将文件信息存储到数据库（代理调用保证 addMediaFilesToDb 事务生效）
        MediaFileService proxy = applicationContext.getBean(MediaFileService.class);
        MediaFiles mediaFiles = proxy.addMediaFilesToDb(companyId, fileMd5, uploadFileParamsDto, bucketFiles, objectName);
        //准备返回数据
        UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
        BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);
        return uploadFileResultDto;
    }

    /**
     * 流式写入 MinIO：putObject 直接消费输入流，不落本地临时文件
     */
    public void addMediaFilesToMinIOStream(InputStream in, long fileSize, String mimeType, String bucket, String objectName) {
        try {
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    //objectSize 必须准确（SDK 据此规划自动分片）；partSize -1 表示 SDK 自动选择
                    .stream(in, fileSize, -1)
                    .contentType(mimeType)
                    .build();
            minioClient.putObject(putObjectArgs);
        } catch (Exception e) {
            log.error("上传文件到文件系统失败,objectName:{},异常:{}", objectName, e.getMessage(), e);
            XueChengPlusException.cast("上传文件到文件系统失败");
        }
    }

    //获取文件默认存储目录路径 年/月/日
    private String getDefaultFolderPath() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new Date()).replace("-", "/") + "/";
    }

    private String getFileMd5(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            return DigestUtils.md5Hex(fileInputStream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getFileMd5(InputStream in) {
        try {
            return DigestUtils.md5Hex(in);
        } catch (Exception e) {
            log.error("计算文件MD5失败,异常:{}", e.getMessage(), e);
            return null;
        }
    }

    private String getMimeType(String extension) {
        if (extension == null)
            extension = "";
        //根据扩展名取出mimeType
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
        //通用mimeType，字节流
        String mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        if (extensionMatch != null) {
            mimeType = extensionMatch.getMimeType();
        }
        return mimeType;
    }

    /**
     * @param localFilePath 文件地址
     * @param bucket        桶
     * @param objectName    对象名称
     * @return boolean
     * {@code @description} 将文件写入minIO
     */
    public boolean addMediaFilesToMinIO(String localFilePath, String mimeType, String bucket, String objectName) {
        try {
            UploadObjectArgs uploadObjectArgs = UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .filename(localFilePath)
                    .contentType(mimeType)
                    .build();
            minioClient.uploadObject(uploadObjectArgs);
            log.debug("上传文件到minio成功,bucket:{},objectName:{}", bucket, objectName);
            System.out.println("上传成功");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("上传文件到minio出错,bucket:{},objectName:{},错误原因:{}", bucket, objectName, e.getMessage(), e);
            XueChengPlusException.cast("上传文件到文件系统失败");
        }
        return false;
    }

    @Transactional
    public MediaFiles addMediaFilesToDb(Long companyId, String fileMd5, UploadFileParamsDto uploadFileParamsDto, String bucket, String objectName) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            mediaFiles = new MediaFiles();
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            mediaFiles.setId(fileMd5);
            mediaFiles.setFileId(fileMd5);
            mediaFiles.setCompanyId(companyId);
            mediaFiles.setBucket(bucket);
            mediaFiles.setFilePath(objectName);
            mediaFiles.setAuditStatus("002003");
            mediaFiles.setStatus("1");
            mediaFiles.setUrl("/" + bucket + "/" + objectName);
            mediaFiles.setCreateDate(LocalDateTime.now());
            int insert = mediaFilesMapper.insert(mediaFiles);
            if (insert <= 0) {
                log.error("保存文件信息到数据库失败,{}", mediaFiles);
                XueChengPlusException.cast("保存文件信息失败");
            }
            addWaitingTask(mediaFiles);
            log.debug("保存文件信息到数据库成功,{}", mediaFiles);
        }
        return mediaFiles;

    }

    public File downloadFileFromMinIO(String bucket, String objectName) {
        File minioFile = null;
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .build())) {
            //创建临时文件
            minioFile = createTempFile("minio", ".merge");
            try (FileOutputStream outputStream = new FileOutputStream(minioFile)) {
                IOUtils.copy(stream, outputStream);
            }
            return minioFile;
        } catch (Exception e) {
            log.debug("下载临时文件失败,异常:{}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public MediaFiles getFileById(String mediaId) {
        return mediaFilesMapper.selectById(mediaId);
    }

    private void addWaitingTask(MediaFiles mediaFiles) {
        //文件名称
        String filename = mediaFiles.getFilename();
        //文件扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        //文件mimeType
        String mimeType = getMimeType(extension);
        //如果是avi视频添加到视频待处理表
        if (mimeType.equals("video/x-msvideo")) {
            MediaProcess mediaProcess = new MediaProcess();
            BeanUtils.copyProperties(mediaFiles, mediaProcess);
            mediaProcess.setStatus("1");//未处理
            mediaProcess.setFailCount(0);//失败次数默认为0
            mediaProcessMapper.insert(mediaProcess);
        }
    }
}
