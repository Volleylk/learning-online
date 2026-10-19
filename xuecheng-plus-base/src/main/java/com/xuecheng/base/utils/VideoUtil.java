package com.xuecheng.base.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 此文件作为视频文件处理父类，提供：
 * 1、查看视频时长
 * 2、校验两个视频的时长是否相等
 *
 */
public class VideoUtil {

    String ffmpeg_path = "D:\\Program Files\\ffmpeg-20180227-fa0c9d6-win64-static\\bin\\ffmpeg.exe";//ffmpeg的安装位置

    public VideoUtil(String ffmpeg_path){
        this.ffmpeg_path = ffmpeg_path;
    }


    //检查视频时间是否一致
    public Boolean check_video_time(String source,String target) {
        String source_time = get_video_time(source);
        String target_time = get_video_time(target);
        if(source_time == null || target_time == null){
            return false;
        }
        //取出时分秒
        int dotIndex1 = source_time.lastIndexOf(".");
        int dotIndex2 = target_time.lastIndexOf(".");
        if (dotIndex1 > 0) {
            source_time = source_time.substring(0, dotIndex1);
        }
        if (dotIndex2 > 0) {
            target_time = target_time.substring(0, dotIndex2);
        }
        if(source_time.equals(target_time)){
            return true;
        }
        return false;
    }

    //获取视频时间(时：分：秒：毫秒)
    public String get_video_time(String video_path) {
        /*
        ffmpeg -i  lucene.mp4
         */
        List<String> commend = new ArrayList<String>();
        commend.add(ffmpeg_path);
        commend.add("-i");
        commend.add(video_path);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(commend);
            //将标准输入流和错误输入流合并，通过标准输入流程读取信息
            builder.redirectErrorStream(true);
            Process p = builder.start();
            String outstring = waitFor(p);
            System.out.println(outstring);
            int start = outstring.trim().indexOf("Duration: ");
            if(start>=0){
                int end = outstring.trim().indexOf(", start:");
                if(end>=0){
                    String time = outstring.substring(start+10,end);
                    if(time!=null && !time.equals("")){
                        return time.trim();
                    }
                }
            }

        } catch (Exception ex) {

            ex.printStackTrace();

        }
        return null;
    }

    public String waitFor(Process p) {
        InputStream in = null;
        StringBuffer outputString = new StringBuffer();
        try {
            in = p.getInputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                outputString.append(new String(buffer, 0, len));
            }
            //等待进程结束并获取退出码
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            }
        }
        return outputString.toString();
    }

    /**
     * 执行ffmpeg命令并返回退出码和输出
     * @param commend 命令列表
     * @return [0]=退出码, [1]=输出日志
     */
    public String[] execCommand(List<String> commend) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(commend);
            builder.redirectErrorStream(true);
            Process p = builder.start();
            String outstring = waitFor(p);
            int exitCode = p.exitValue();
            return new String[]{String.valueOf(exitCode), outstring};
        } catch (Exception e) {
            e.printStackTrace();
            return new String[]{"-1", e.getMessage()};
        }
    }


    public static void main(String[] args) throws IOException {
        String ffmpeg_path = "D:\\Program Files\\ffmpeg-20180227-fa0c9d6-win64-static\\bin\\ffmpeg.exe";//ffmpeg的安装位置
        VideoUtil videoUtil = new VideoUtil(ffmpeg_path);
        String video_time = videoUtil.get_video_time("E:\\ffmpeg_test\\1.avi");
        System.out.println(video_time);
    }
}
