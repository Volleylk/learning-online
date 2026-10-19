package com.xuecheng.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xuecheng.content.model.dto.TeachplanDto;
import com.xuecheng.content.model.po.Teachplan;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TeachplanMapper extends BaseMapper<Teachplan> {

    /**
     * @param courseId
     * @return com.xuecheng.content.model.dto.TeachplanDto
     * @description 查询某课程的课程计划，组成树型结构
     */
    List<TeachplanDto> selectTreeNodes(long courseId);

    /**
     * 找到最大排序
     * @param courseId
     * @param parentId
     * @return
     */
    @Select("select MAX(orderby) from teachplan where course_id=#{courseId} and parentid=#{parentId}")
    Integer selectMaxOrder(@Param("courseId") Long courseId, @Param("parentId") Long parentId);

    /**
     * 根据父节点id和课程id找子节点的个数
     * 相比查询所有子节点的集合，只查询个数，优化保存课程计划的效率
     * @param parentid
     * @param courseId
     * @return
     */
    @Select("SELECT COUNT(1) FROM teachplan WHERE parentid = #{parentid} and course_id=#{courseId}")
    Long countByParentId(@Param("parentid") Long parentid, @Param("courseId")Long courseId);

    /**
     * 查找上方相邻节点（同父节点，orderby比当前小的最大一条）
     */
    @Select("select * from teachplan where course_id=#{courseId} and parentid=#{parentId} and orderby < #{orderby} order by orderby desc limit 1")
    Teachplan findUp(@Param("courseId") Long courseId,
                             @Param("parentId") Long parentId,
                             @Param("orderby") Integer orderby);

    /**
     * 查找下方相邻节点（同父节点，orderby比当前大的最小一条）
     */
    @Select("select * from teachplan where course_id=#{courseId} and parentid=#{parentId} and orderby > #{orderby} order by orderby asc limit 1")
    Teachplan findDown(@Param("courseId") Long courseId,
                               @Param("parentId") Long parentId,
                               @Param("orderby") Integer orderby);

    @Delete("delete from teachplan where course_id=#{courseId}")
    void deleteByCourseId(Long courseId);
}