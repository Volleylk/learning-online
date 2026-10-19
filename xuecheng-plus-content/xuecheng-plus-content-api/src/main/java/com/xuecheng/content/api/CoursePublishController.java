package com.xuecheng.content.api;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.xuecheng.content.model.dto.CoursePreviewDto;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.content.service.CoursePublishService;
import com.xuecheng.content.util.SecurityUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Api(value = "课程发布管理接口", tags = "课程发布管理接口")
@RestController
@RequiredArgsConstructor
public class CoursePublishController {

    private final CoursePublishService coursePublishService;

    @GetMapping("/coursepreview/{courseId}")
    public ModelAndView preview(@PathVariable("courseId") Long courseId) {

        //获取课程预览信息
        CoursePreviewDto coursePreviewInfo = coursePublishService.getCoursePreviewInfo(courseId);

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("model", coursePreviewInfo);
        modelAndView.setViewName("course_template");
        return modelAndView;
    }

    @ResponseBody
    @PreAuthorize("hasAuthority('xc_teachmanager_course_publish')")//拥有发布课程的权限方可访问
    @PostMapping("/courseaudit/commit/{courseId}")
    public void commitAudit(@PathVariable("courseId") Long courseId) {
        SecurityUtil.XcUser user = SecurityUtil.getUser();
        if (user == null || StringUtils.isBlank(user.getCompanyId())) {
            throw new RuntimeException("获取当前登录用户身份失败");
        }
        Long companyId = Long.valueOf(user.getCompanyId());
        coursePublishService.commitAudit(companyId, courseId);

    }

    @ApiOperation("课程发布")
    @ResponseBody
    @PreAuthorize("hasAuthority('xc_teachmanager_course_publish')")//拥有发布课程的权限方可访问
    @PostMapping ("/coursepublish/{courseId}")
    public void coursepublish(@PathVariable("courseId") Long courseId){
        SecurityUtil.XcUser user = SecurityUtil.getUser();
        if (user == null || StringUtils.isBlank(user.getCompanyId())) {
            throw new RuntimeException("获取当前登录用户身份失败");
        }
        Long companyId = Long.valueOf(user.getCompanyId());
        coursePublishService.publish(companyId,courseId);

    }

    @ApiOperation("查询课程发布信息(供内部远程调用)")
    @ResponseBody
    @GetMapping("/r/coursepublish/{courseId}")
    public CoursePublish getCoursepublish(@PathVariable("courseId") Long courseId) {
        return coursePublishService.getCoursePublish(courseId);
    }

}
