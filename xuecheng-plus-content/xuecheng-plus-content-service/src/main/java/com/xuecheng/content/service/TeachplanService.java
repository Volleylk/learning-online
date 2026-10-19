package com.xuecheng.content.service;

import com.xuecheng.content.model.dto.*;
import com.xuecheng.content.model.po.TeachplanMedia;

import java.util.List;

/**
 * @description 课程基本信息管理业务接口
 */
public interface TeachplanService {

    /**
     * @description 查询课程计划树型结构
     * @param courseId  课程id
     * @return List<TeachplanDto>
     */
    List<TeachplanDto> findTeachplanTree(long courseId);

    /**
     * @description 保存课程计划
     * @param teachplanDto  课程计划信息
     * @return void
     */
    void saveTeachplan(SaveTeachplanDto teachplanDto);

    /**
     * 删除课程计划
     * @param id
     */
    void deleteTeachplan(Long id);

    /**
     *向上移动课程计划
     * @param id
     */
    void moveUpTeachplan(Long id);

    /**
     * 向下移动课程计划
     * @param id
     */
    void moveDownTeachplan(Long id);

    /**
     * @description 教学计划绑定媒资
     * @param *bindTeachplanMediaDto*
     * ** @return com.xuecheng.content.model.po.TeachplanMedia
     */
    TeachplanMedia associationMedia(BindTeachplanMediaDto bindTeachplanMediaDto);

    /**
     * @description 教学计划解除绑定媒资
     * @param teachplanId 教学计划id
     * @param mediaId 媒资id
     */
    void unbindMedia(Long teachplanId, String mediaId);

}