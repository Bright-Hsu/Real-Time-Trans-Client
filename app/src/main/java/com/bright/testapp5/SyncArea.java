package com.bright.testapp5;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public class SyncArea {
    private static final String TAG = "TestApp5";

    public synchronized static void sendImage(byte[] data, int format, Camera.Size size, Socket socket) {
        try {
            YuvImage image = new YuvImage(data, format, size.width, size.height, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // 设置图片质量和尺寸，将NV21格式图片压缩成Jpeg，并得到JPEG数据流
            if(!image.compressToJpeg(new Rect(0, 0, size.width, size.height), 100, baos)){
                Log.e(TAG, "onPreviewFrame Yuv Image compressToJpeg failed");
                return;
            }
            byte[] imageData = baos.toByteArray();
            // 发送图片大小
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            dos.writeInt(imageData.length);
            dos.flush();
            // 发送图片数据
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(imageData);
            outputStream.flush();
            // 释放掉image的内存
            image = null;
            baos.flush();
            baos.close();
            System.gc();
            Log.d(TAG, "onPreviewFrame jpeg size: " + imageData.length);
        } catch (
                IOException e) {
            // 打印错误信息
            Log.e(TAG, "onPreviewFrame Error: " + e.getMessage());
            e.printStackTrace();
            // throw new RuntimeException(e);
        }
    }
}
