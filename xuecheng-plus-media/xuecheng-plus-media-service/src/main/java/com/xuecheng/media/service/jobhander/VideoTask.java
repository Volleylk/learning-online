package com.xuecheng.media.service.jobhander;

import com.xuecheng.base.utils.Mp4VideoUtil;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileProcessService;
import com.xuecheng.media.service.MediaFileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 视频处理任务处理器
 *
 * @author Mr.M
 * @version 1.0
 * @date 2022/10/15 11:58
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTask {

    private final MediaFileService mediaFileService;

    private final MediaFileProcessService mediaFileProcessService;

    @Value("${videoprocess.ffmpegpath}")
    private String ffmpegpath;

    @XxlJob("videoJobHandler")
    public void videoJobHandler() throws Exception {
        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        List<MediaProcess> mediaProcessList = null;
        int size = 0;

        try {
            // 取出CPU核心数作为一次处理数据的条数
            int processors = Runtime.getRuntime().availableProcessors();
            // 一次处理视频数量不要超过CPU核心数
            mediaProcessList = mediaFileProcessService.getMediaProcessList(shardIndex, shardTotal, processors);
            size = mediaProcessList.size();
            log.debug("取出待处理视频任务{}条", size);
            if (size <= 0) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // 启动size个线程的固定线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(size);
        // 计数器，用于等待所有任务完成
        CountDownLatch countDownLatch = new CountDownLatch(size);

        // 将处理任务提交到线程池
        mediaProcessList.forEach(mediaProcess -> {
            threadPool.execute(() -> {
                try {
                    // 任务ID
                    Long taskId = mediaProcess.getId();
                    // 抢占任务
                    boolean taskStart = mediaFileProcessService.startTask(taskId);
                    if (!taskStart) {
                        return;
                    }
                    log.debug("开始执行任务:{}", mediaProcess);

                    // ========== 视频处理逻辑 ==========
                    // MinIO桶名
                    String bucket = mediaProcess.getBucket();
                    // 文件存储路径
                    String filePath = mediaProcess.getFilePath();
                    // 原始视频的md5值
                    String fileId = mediaProcess.getFileId();
                    // 原始文件名称
                    String filename = mediaProcess.getFilename();

                    // 从MinIO下载待处理文件到本地
                    File originalFile = mediaFileService.downloadFileFromMinIO(
                            mediaProcess.getBucket(),
                            mediaProcess.getFilePath()
                    );
                    if (originalFile == null) {
                        log.debug("下载待处理文件失败, 文件路径:{}",
                                mediaProcess.getBucket().concat(mediaProcess.getFilePath()));
                        mediaFileProcessService.saveProcessFinishStatus(
                                mediaProcess.getId(),
                                "3",
                                fileId,
                                null,
                                "下载待处理文件失败"
                        );
                        return;
                    }

                    // 创建临时mp4文件
                    File mp4File = null;
                    try {
                        mp4File = File.createTempFile("mp4", ".mp4");
                    } catch (IOException e) {
                        log.error("创建mp4临时文件失败");
                        mediaFileProcessService.saveProcessFinishStatus(
                                mediaProcess.getId(),
                                "3",
                                fileId,
                                null,
                                "创建mp4临时文件失败"
                        );
                        return;
                    }

                    // 执行视频转码
                    String result = "";
                    try {
                        Mp4VideoUtil videoUtil = new Mp4VideoUtil(
                                ffmpegpath,
                                originalFile.getAbsolutePath(),
                                mp4File.getName(),
                                mp4File.getAbsolutePath()
                        );
                        // 转码成功返回 success
                        result = videoUtil.generateMp4();
                    } catch (Exception e) {
                        e.printStackTrace();
                        log.error("处理视频文件:{}, 出错:{}", mediaProcess.getFilePath(), e.getMessage());
                    }

                    // 转码失败处理
                    if (!"success".equals(result)) {
                        log.error("处理视频失败, 视频地址:{}, 错误信息:{}", bucket + filePath, result);
                        mediaFileProcessService.saveProcessFinishStatus(
                                mediaProcess.getId(),
                                "3",
                                fileId,
                                null,
                                result
                        );
                        return;
                    }

                    // ========== 上传转码后的视频到MinIO ==========
                    String objectName = getFilePath(fileId, ".mp4");
                    String url = "/" + bucket + "/" + objectName;

                    try {
                        mediaFileService.addMediaFilesToMinIO(
                                mp4File.getAbsolutePath(),
                                "video/mp4",
                                bucket,
                                objectName
                        );
                        // 更新任务状态为成功，迁移记录到历史表
                        mediaFileProcessService.saveProcessFinishStatus(
                                mediaProcess.getId(),
                                "2",
                                fileId,
                                url,
                                null
                        );
                    } catch (Exception e) {
                        log.error("上传视频失败或入库失败, 视频地址:{}, 错误:",
                                bucket + objectName, e);
                        // 截取前200个字符，避免数据库字段长度不够
                        String errorMsg = e.getMessage();
                        mediaFileProcessService.saveProcessFinishStatus(
                                mediaProcess.getId(),
                                "3",
                                fileId,
                                null,
                                "上传/入库失败: " + errorMsg
                        );
                    }

                } finally {
                    // 任务完成，计数器减1
                    countDownLatch.countDown();
                }
            });
        });

        // 等待所有任务完成，设置30分钟超时防止无限阻塞
        countDownLatch.await(30, TimeUnit.MINUTES);
    }

    /**
     * 根据文件MD5和扩展名构建MinIO存储路径
     *
     * @param fileMd5 文件MD5值
     * @param fileExt 文件扩展名
     * @return MinIO中的完整存储路径
     */
    private String getFilePath(String fileMd5, String fileExt) {
        return
                fileMd5.charAt(0) + "/" +
                        fileMd5.charAt(1) + "/" +
                        fileMd5 + fileExt;
    }


}
