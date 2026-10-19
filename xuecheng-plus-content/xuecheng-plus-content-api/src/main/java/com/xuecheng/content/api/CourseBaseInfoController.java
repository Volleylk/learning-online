package com.xuecheng.content.api;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.xuecheng.base.exception.ValidationGroups;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.content.model.dto.AddCourseDto;
import com.xuecheng.content.model.dto.CourseBaseInfoDto;
import com.xuecheng.content.model.dto.EditCourseDto;
import com.xuecheng.content.model.dto.QueryCourseParamsDto;
import com.xuecheng.content.model.po.CourseBase;
import com.xuecheng.content.service.CourseBaseInfoService;
import com.xuecheng.content.util.SecurityUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


/**
 * @description 课程信息管理接口
 */
@Api(value = "课程信息管理接口",tags = "课程信息管理接口")
@RestController
@RequestMapping("/course")
@RequiredArgsConstructor
public class CourseBaseInfoController {
    private final CourseBaseInfoService courseBaseInfoService;

    @ApiOperation("课程查询接口")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_list')")//拥有课程列表查询的权限方可访问
    @PostMapping("/list")
    public PageResult<CourseBase> list(@ModelAttribute PageParams pageParams, @RequestBody QueryCourseParamsDto queryCourseParams){
        PageResult<CourseBase> pageResult = courseBaseInfoService.queryCourseBaseList(pageParams, queryCourseParams);
        return pageResult;
    }
    @ApiOperation("新增课程基础信息")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_add')")//拥有新增课程的权限方可访问
    @PostMapping
    public CourseBaseInfoDto createCourseBase(@RequestBody @Validated({ValidationGroups.Insert.class}) AddCourseDto addCourseDto){
        //机构id，由于认证系统没有上线暂时硬编码
        SecurityUtil.XcUser user = SecurityUtil.getUser();
        if (user == null || StringUtils.isBlank(user.getCompanyId())) {
            throw new RuntimeException("获取当前登录用户身份失败");
        }
        Long companyId = Long.valueOf(user.getCompanyId());
        return courseBaseInfoService.createCourseBase(companyId,addCourseDto);
    }
    @ApiOperation("根据课程id查询课程基础信息")
    @GetMapping("/{courseId}")
    public CourseBaseInfoDto getCourseBaseById(@PathVariable Long courseId){
        return courseBaseInfoService.getCourseBaseInfo(courseId);
    }

    @ApiOperation("修改课程基础信息")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_base')")//拥有编辑课程基础信息的权限方可访问
    @PutMapping
    public CourseBaseInfoDto modifyCourseBase(@RequestBody @Validated EditCourseDto editCourseDto){
        SecurityUtil.XcUser user = SecurityUtil.getUser();
        if (user == null || StringUtils.isBlank(user.getCompanyId())) {
            throw new RuntimeException("获取当前登录用户身份失败");
        }
        Long companyId = Long.valueOf(user.getCompanyId());
        return courseBaseInfoService.updateCourseBase(companyId,editCourseDto);
    }

    @ApiOperation("删除课程信息")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_del')")//拥有删除课程的权限方可访问
    @DeleteMapping("{id}")
    public void deleteCourseBase(@PathVariable Long id){
        SecurityUtil.XcUser user = SecurityUtil.getUser();
        if (user == null || StringUtils.isBlank(user.getCompanyId())) {
            throw new RuntimeException("获取当前登录用户身份失败");
        }
        Long companyId = Long.valueOf(user.getCompanyId());
        courseBaseInfoService.deleteCourseBase(companyId,id);
    }
}
