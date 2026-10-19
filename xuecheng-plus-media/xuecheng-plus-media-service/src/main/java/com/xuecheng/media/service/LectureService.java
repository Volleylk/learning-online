package com.xuecheng.media.service;

import com.xuecheng.media.model.po.LectureParseStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 讲义上传服务：讲义文件入 MinIO + 发 RabbitMQ 通知 AI 服务解析。
 */
public interface LectureService {

    /**
     * 上传讲义并发送解析消息。
     *
     * @param file       讲义文件（PDF/Word/Markdown 等）
     * @param courseId   所属课程ID
     * @param courseName 所属课程名称
     * @param fileId     讲义ID（可为空，为空时自动生成 UUID）
     * @return 处理结果描述
     */
    String uploadLecture(MultipartFile file, String courseId, String courseName, String fileId) throws Exception;

    /**
     * 记录 AI 服务解析讲义的状态回调（success/failed）。
     *
     * @param fileId  讲义ID
     * @param status  解析状态：success / failed
     * @param message 状态描述
     */
    void recordParseStatus(String fileId, String status, String message);

    /**
     * 查询讲义解析状态（持久化 MySQL，media 重启不丢失）。
     *
     * @param fileId 讲义ID
     * @return 解析状态记录；不存在返回 null
     */
    LectureParseStatus getParseStatus(String fileId);

    /**
     * 按课程查询讲义解析状态列表（课程讲义管理页用）。
     *
     * @param courseId 课程ID（可为空，空时返回全部，按更新时间倒序）
     * @return 讲义解析状态列表
     */
    List<LectureParseStatus> listByCourse(Long courseId);
}
