package com.xuecheng.content.api;

import com.xuecheng.base.exception.RestErrorResponse;
import com.xuecheng.content.model.dto.AddCourseTeacherDto;
import com.xuecheng.content.model.dto.EditCourseTeacherDto;
import com.xuecheng.content.model.po.CourseTeacher;
import com.xuecheng.content.service.CourseTeacherService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/courseTeacher")
@RequiredArgsConstructor
@Api(value = "课程教师管理接口", tags = "课程教师管理接口")
public class CourseTeacherController {
    private final CourseTeacherService courseTeacherService;

    @ApiOperation("课程教师查询接口")
    @GetMapping("/list/{id}")
    public List<CourseTeacher> list(@PathVariable Long id){
        return courseTeacherService.list(id);
    }

    @ApiOperation("课程教师添加接口")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_base')")//拥有编辑课程基础信息的权限方可访问
    @PostMapping
    public CourseTeacher save(@RequestBody @Validated AddCourseTeacherDto addCourseTeacherDto){
        return courseTeacherService.save(addCourseTeacherDto);
    }

    @ApiOperation("课程教师修改接口")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_base')")//拥有编辑课程基础信息的权限方可访问
    @PutMapping
    public CourseTeacher modifyCourseTeacher(@RequestBody @Validated EditCourseTeacherDto editcourseTeacherDto){
        return courseTeacherService.updateCourseTeacher(editcourseTeacherDto);
    }

    @ApiOperation("课程教师删除接口")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_base')")//拥有编辑课程基础信息的权限方可访问
    @DeleteMapping("/course/{courseId}/{id}")
    public void deleteCourseTeacher(@PathVariable Long courseId, @PathVariable Long id){
        courseTeacherService.deleteCourseTeacher(courseId,id);
    }
}
