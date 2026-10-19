package com.xuecheng.base.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Mp4VideoUtil extends VideoUtil {

    String ffmpeg_path = "D:\\Program Files\\ffmpeg-20180227-fa0c9d6-win64-static\\bin\\ffmpeg.exe";//ffmpeg的安装位置
    String video_path = "D:\\BaiduNetdiskDownload\\test1.avi";
    String mp4_name = "test1.mp4";
    String mp4folder_path = "D:/BaiduNetdiskDownload/Movies/test1/";
    public Mp4VideoUtil(String ffmpeg_path, String video_path, String mp4_name, String mp4folder_path){
        super(ffmpeg_path);
        this.ffmpeg_path = ffmpeg_path;
        this.video_path = video_path;
        this.mp4_name = mp4_name;
        this.mp4folder_path = mp4folder_path;
    }
    //清除已生成的mp4
    private void clear_mp4(String mp4_path){
        //删除原来已经生成的m3u8及ts文件
        File mp4File = new File(mp4_path);
        if(mp4File.exists() && mp4File.isFile()){
            mp4File.delete();
        }
    }
    /**
     * 视频编码，生成mp4文件
     * @return 成功返回success，失败返回错误信息
     */
    public String generateMp4(){
        //清除已生成的mp4
        clear_mp4(mp4folder_path);
        /*
        ffmpeg.exe -i  input.avi -c:v libx264 -s 1280x720 -pix_fmt yuv420p -b:a 63k -b:v 753k -r 18 output.mp4
         */
        List<String> commend = new ArrayList<String>();
        commend.add(ffmpeg_path);
        commend.add("-i");
        commend.add(video_path);
        commend.add("-c:v");
        commend.add("libx264");
        commend.add("-y");//覆盖输出文件
        commend.add("-s");
        commend.add("1280x720");
        commend.add("-pix_fmt");
        commend.add("yuv420p");
        commend.add("-b:a");
        commend.add("63k");
        commend.add("-b:v");
        commend.add("753k");
        commend.add("-r");
        commend.add("18");
        //输出文件路径（完整路径）
        commend.add(mp4folder_path);

        //执行命令
        String[] result = execCommand(commend);
        int exitCode = Integer.parseInt(result[0]);
        String outstring = result[1];

        //退出码0表示成功
        if (exitCode == 0) {
            //额外校验：输出文件必须存在且大小大于0
            File outputFile = new File(mp4folder_path);
            if (outputFile.exists() && outputFile.length() > 0) {
                return "success";
            } else {
                return "转码成功但输出文件不存在或为空";
            }
        } else {
            //提取错误信息
            String errorMsg = outstring;
            if (outstring.length() > 500) {
                errorMsg = outstring.substring(outstring.length() - 500);
            }
            return "ffmpeg转码失败，退出码：" + exitCode + "，错误信息：" + errorMsg;
        }
    }

    public static void main(String[] args) throws IOException {
        //ffmpeg的路径
        String ffmpeg_path = "D:\\soft\\ffmpeg\\ffmpeg.exe";//ffmpeg的安装位置
        //源avi视频的路径
        String video_path = "D:\\develop\\bigfile_test\\nacos01.avi";
        //转换后mp4文件的名称
        String mp4_name = "nacos01.mp4";
        //转换后mp4文件的路径
        String mp4_path = "D:\\develop\\bigfile_test\\";
        //创建工具类对象
        Mp4VideoUtil videoUtil = new Mp4VideoUtil(ffmpeg_path,video_path,mp4_name,mp4_path);
        //开始视频转换，成功将返回success
        String s = videoUtil.generateMp4();
        System.out.println(s);
    }
}