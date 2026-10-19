package com.xuecheng.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.content.mapper.TeachplanMapper;
import com.xuecheng.content.mapper.TeachplanMediaMapper;
import com.xuecheng.content.model.dto.BindTeachplanMediaDto;
import com.xuecheng.content.model.dto.SaveTeachplanDto;
import com.xuecheng.content.model.dto.TeachplanDto;
import com.xuecheng.content.model.po.Teachplan;
import com.xuecheng.content.model.po.TeachplanMedia;
import com.xuecheng.content.service.TeachplanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程计划service接口实现类
 */
@Service
@RequiredArgsConstructor
public class TeachplanServiceImpl implements TeachplanService {

    private final TeachplanMapper teachplanMapper;
    private final TeachplanMediaMapper teachplanMediaMapper;


    @Override
    public List<TeachplanDto> findTeachplanTree(long courseId) {
        return teachplanMapper.selectTreeNodes(courseId);
    }

    @Transactional
    @Override
    public void saveTeachplan(SaveTeachplanDto teachplanDto) {

        //课程计划id
        Long id = teachplanDto.getId();
        //修改课程计划
        if (id != null) {
            Teachplan teachplan = teachplanMapper.selectById(id);
            BeanUtils.copyProperties(teachplanDto, teachplan);
            teachplanMapper.updateById(teachplan);
        } else {
            Teachplan teachplanNew = new Teachplan();
            BeanUtils.copyProperties(teachplanDto, teachplanNew);
            //设置排序号
            int count = getTeachplanCount(teachplanDto.getCourseId(), teachplanDto.getParentid());
            teachplanNew.setOrderby(count + 1);

            teachplanMapper.insert(teachplanNew);
        }
    }

    /**
     * 删除课程计划
     *
     * @param id 章节id
     */
    @Override
    @Transactional
    public void deleteTeachplan(Long id) {
        Teachplan teachplan = teachplanMapper.selectById(id);
        if (teachplan == null) {
            XueChengPlusException.cast("课程计划不存在");
        }
        //删除大章节
        if (teachplan.getGrade() == 1) {
            Long count = teachplanMapper.countByParentId(teachplan.getId(), teachplan.getCourseId());
            if (count > 0) {
                XueChengPlusException.cast("大章节下有小章节时不允许删除");
            } else {
                teachplanMapper.deleteById(id);
            }
        } else {//删除小章节
            TeachplanMedia teachplanMedia = teachplanMediaMapper.selectByTeachplanId(id);
            if (teachplanMedia != null) {
                teachplanMediaMapper.deleteById(teachplanMedia.getId());
            }
            teachplanMapper.deleteById(id);
        }
    }

    @Transactional
    @Override
    public void moveUpTeachplan(Long id) {
        Teachplan teachplan = teachplanMapper.selectById(id);
        if (teachplan == null) {
            XueChengPlusException.cast("课程计划不存在");
        }
        // 查询上邻节点
        Teachplan upNeighbor = teachplanMapper.findUp(
                teachplan.getCourseId(),
                teachplan.getParentid(),
                teachplan.getOrderby()
        );
        // 没有上邻节点，不能上移
        if (upNeighbor == null) {
            XueChengPlusException.cast("最上方的课程计划无法上移");
        }
        // 交换orderby
        Integer temp = teachplan.getOrderby();
        teachplan.setOrderby(upNeighbor.getOrderby());
        upNeighbor.setOrderby(temp);
        teachplanMapper.updateById(teachplan);
        teachplanMapper.updateById(upNeighbor);
    }

    @Transactional
    @Override
    public void moveDownTeachplan(Long id) {
        Teachplan teachplan = teachplanMapper.selectById(id);
        if (teachplan == null) {
            XueChengPlusException.cast("课程计划不存在");
        }
        // 查询下邻节点
        Teachplan downNeighbor = teachplanMapper.findDown(
                teachplan.getCourseId(),
                teachplan.getParentid(),
                teachplan.getOrderby()
        );
        if (downNeighbor == null) {
            XueChengPlusException.cast("最下方的课程计划无法下移");
        }
        Integer temp = teachplan.getOrderby();
        teachplan.setOrderby(downNeighbor.getOrderby());
        downNeighbor.setOrderby(temp);
        teachplanMapper.updateById(teachplan);
        teachplanMapper.updateById(downNeighbor);
    }

    @Transactional
    @Override
    public TeachplanMedia associationMedia(BindTeachplanMediaDto bindTeachplanMediaDto) {
        //教学计划id
        Long teachplanId = bindTeachplanMediaDto.getTeachplanId();
        Teachplan teachplan = teachplanMapper.selectById(teachplanId);
        if (teachplan == null) {
            XueChengPlusException.cast("教学计划不存在");
        }
        Integer grade = teachplan.getGrade();
        if (grade != 2) {
            XueChengPlusException.cast("只允许第二级教学计划绑定媒资文件");
        }
        //课程id
        Long courseId = teachplan.getCourseId();

        //先删除原来该教学计划绑定的媒资
        teachplanMediaMapper.delete(new LambdaQueryWrapper<TeachplanMedia>().eq(TeachplanMedia::getTeachplanId, teachplanId));

        //再添加教学计划与媒资的绑定关系
        TeachplanMedia teachplanMedia = new TeachplanMedia();
        teachplanMedia.setCourseId(courseId);
        teachplanMedia.setTeachplanId(teachplanId);
        teachplanMedia.setMediaFilename(bindTeachplanMediaDto.getFileName());
        teachplanMedia.setMediaId(bindTeachplanMediaDto.getMediaId());
        teachplanMedia.setCreateDate(LocalDateTime.now());
        teachplanMediaMapper.insert(teachplanMedia);
        return teachplanMedia;
    }

    /**
     * @description 教学计划解除绑定媒资
     * @param teachplanId 教学计划id
     * @param mediaId 媒资id
     */
    @Override
    public void unbindMedia(Long teachplanId, String mediaId) {
        //根据教学计划id和媒资id删除绑定关系
        teachplanMediaMapper.delete(new LambdaQueryWrapper<TeachplanMedia>()
                .eq(TeachplanMedia::getTeachplanId, teachplanId)
                .eq(TeachplanMedia::getMediaId, mediaId));
    }


    /**
     * @param courseId 课程id
     * @param parentId 父课程计划id
     * @return int 最新排序号
     */
    private int getTeachplanCount(long courseId, long parentId) {
        Integer maxOrder = teachplanMapper.selectMaxOrder(courseId, parentId);
        return maxOrder == null ? 0 : maxOrder;
    }
}