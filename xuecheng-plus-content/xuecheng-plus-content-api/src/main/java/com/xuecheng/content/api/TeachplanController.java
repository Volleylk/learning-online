package com.xuecheng.content.api;

import com.xuecheng.content.model.dto.BindTeachplanMediaDto;
import com.xuecheng.content.model.dto.SaveTeachplanDto;
import com.xuecheng.content.model.dto.TeachplanDto;
import com.xuecheng.content.service.TeachplanService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @description 课程计划编辑接口
 */
@Api(value = "课程计划编辑接口",tags = "课程计划编辑接口")
@RestController
@RequestMapping("/teachplan")
@RequiredArgsConstructor
public class TeachplanController {

    private final TeachplanService teachplanService;

    @ApiOperation("查询课程计划树形结构")
    @ApiImplicitParam(value = "courseId", name = "课程基础Id值", required = true, dataType = "Long", paramType = "path")
    @GetMapping("{courseId}/tree-nodes")
    public List<TeachplanDto> getTreeNodes(@PathVariable Long courseId) {
        return teachplanService.findTeachplanTree(courseId);
    }

    @ApiOperation("课程计划创建或修改")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @PostMapping
    public void saveTeachplan(@RequestBody SaveTeachplanDto teachplan) {
        teachplanService.saveTeachplan(teachplan);
    }

    @ApiOperation("删除课程计划")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @DeleteMapping("/{id}")
    public void deleteTeachplan(@PathVariable Long id) {
        teachplanService.deleteTeachplan(id);
    }

    @ApiOperation("向上移动课程计划")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @PostMapping("/moveup/{id}")
    public void moveUpTeachplan(@PathVariable Long id) {
        teachplanService.moveUpTeachplan(id);
    }

    @ApiOperation("向下移动课程计划")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @PostMapping("/movedown/{id}")
    public void moveDownTeachplan(@PathVariable Long id) {
        teachplanService.moveDownTeachplan(id);
    }
    @ApiOperation(value = "课程计划和媒资信息绑定")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @PostMapping("/association/media")
    public void associationMedia(@RequestBody BindTeachplanMediaDto bindTeachplanMediaDto){
        teachplanService.associationMedia(bindTeachplanMediaDto);
    }

    @ApiOperation(value = "课程计划和媒资解除绑定")
    @PreAuthorize("hasAuthority('xc_teachmanager_course_plan')")//拥有编辑课程计划的权限方可访问
    @DeleteMapping("/association/media/{teachPlanId}/{mediaId}")
    public void unbindMedia(@PathVariable Long teachPlanId, @PathVariable String mediaId){
        teachplanService.unbindMedia(teachPlanId, mediaId);
    }
}