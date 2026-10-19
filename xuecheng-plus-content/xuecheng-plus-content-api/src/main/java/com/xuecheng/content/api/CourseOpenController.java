package com.xuecheng.content.api;

import com.xuecheng.content.model.dto.CoursePreviewDto;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.content.service.CourseBaseInfoService;
import com.xuecheng.content.service.CoursePublishService;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Api(value = "课程公开查询接口",tags = "课程公开查询接口")
@RestController
@RequestMapping("/open")
@RequiredArgsConstructor
public class CourseOpenController {

    private final CourseBaseInfoService courseBaseInfoService;

    private final CoursePublishService coursePublishService;

    @GetMapping("/course/whole/{courseId}")
    public CoursePreviewDto getPreviewInfo(@PathVariable("courseId") Long courseId) {
        //获取课程预览信息
        CoursePreviewDto coursePreviewInfo = coursePublishService.getCoursePreviewInfo(courseId);
        return coursePreviewInfo;
    }

    /**
     * @description 供 AI Agent 检索已发布课程（免权限，走 /open/** 公开路由）
     * @param keyword 课程关键词，可选
     */
    @GetMapping("/course/agent/list")
    public List<CoursePublish> getAgentCourseList(@RequestParam(value = "keyword", required = false) String keyword) {
        return coursePublishService.listAgentCourses(keyword);
    }

}