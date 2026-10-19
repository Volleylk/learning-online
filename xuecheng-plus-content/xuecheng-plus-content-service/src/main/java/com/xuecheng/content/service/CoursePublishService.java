package com.xuecheng.content.service;

import com.xuecheng.content.model.dto.CoursePreviewDto;
import com.xuecheng.content.model.po.CoursePublish;

import java.io.File;
import java.util.List;

/**
 * @description 课程预览、发布接口
 */
public interface CoursePublishService {

    /**
     * @description 获取课程预览信息
     * @param courseId 课程id
     * @return com.xuecheng.content.model.dto.CoursePreviewDto
     */
    CoursePreviewDto getCoursePreviewInfo(Long courseId);

    /**
     * @description 提交审核
     * @param courseId  课程id
     * @return void
     * @author Mr.M
     * @date 2022/9/18 10:31
     */
    public void commitAudit(Long companyId,Long courseId);

    /**
     * @description 课程发布接口
     * @param companyId 机构id
     * @param courseId 课程id
     * @return void
     * @author Mr.M
     * @date 2022/9/20 16:23
     */
    public void publish(Long companyId,Long courseId);

    /**
     * @description 课程静态化，生成静态化页面文件
     * @param courseId 课程id
     * @return File 静态化文件
     */
    public File generateCourseHtml(Long courseId);

    /**
     * @description 上传课程静态化页面到文件系统
     * @param courseId 课程id
     * @param file     静态化文件
     */
    public void uploadCourseHtml(Long courseId, File file);

    /**
     * @description 按课程id查询课程发布信息
     */
    public CoursePublish getCoursePublish(Long courseId);

    /**
     * @description 供 AI Agent 检索已发布课程（免权限）
     * @param keyword 课程关键词，可为空（空则返回全部已发布课程）
     * @return 已发布课程列表（最多10条）
     */
    List<CoursePublish> listAgentCourses(String keyword);
}
