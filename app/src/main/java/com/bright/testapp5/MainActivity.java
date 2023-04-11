package com.bright.testapp5;

import static java.lang.Thread.sleep;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.provider.Settings;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.sql.Time;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "TestApp5";
    private SurfaceView mSurfaceview = null; // SurfaceView对象：(视图组件)视频显示
    private SurfaceHolder mSurfaceHolder = null; // SurfaceHolder对象：(抽象接口)SurfaceView支持类
    private Camera mCamera = null; // Camera对象，相机预览
    private Button myBtn01 = null; // 按钮btn_connect
    private Button myBtn02 = null; // 按钮btn_trans
    private EditText myEdit01 = null; // 编辑框edit_ip
    private int screenWidth = 640; // 屏幕宽度
    private int screenHeight = 480; // 屏幕高度
    private boolean isSending = false; // 是否在发送视频中
    private boolean isConnecting = false; // 是否在连接中

    private String serverIP = "192.168.1.102"; // 服务器IP地址
    private int serverPort = 8888; // 服务器端口号
    private Socket socket;
    private OutputStream outputStream;
    private DataOutputStream dos;

    private static byte byteBuffer[] = new byte[1024 * 64]; // 用于存放图片数据的缓冲区

    private ReentrantLock lock = new ReentrantLock();

    private RenderScript rs;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private Type.Builder yuvType, rgbaType;
    private Allocation in, out;

    Thread socketThread = null;
    BlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
    private long totalSize = 0;


    private static final String[] permission = new String[]{
            // 相机
            Manifest.permission.CAMERA,
            // 网络
            Manifest.permission.INTERNET,
            // 网络状态
            Manifest.permission.ACCESS_NETWORK_STATE,
            // WIFI状态
            Manifest.permission.ACCESS_WIFI_STATE,
            // 系统CPU使用情况
            Manifest.permission.WAKE_LOCK,
            // 读取手机状态
            Manifest.permission.READ_PHONE_STATE,
            // 读取手机状态
            Manifest.permission.READ_EXTERNAL_STORAGE,
            // 录音
            Manifest.permission.RECORD_AUDIO,
            // 写入手机存储
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //禁止屏幕休眠
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        rs = RenderScript.create(this);
        yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

        // Android 6.0相机动态权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        } else {
            ActivityCompat.requestPermissions(this, permission, 1);
        }

        mSurfaceview = findViewById(R.id.sfv_preview); // 获取界面中SurfaceView组件
        mSurfaceview.setKeepScreenOn(true); // 屏幕常亮
        // 打印SurfaceView的宽高
        Log.i(TAG, "SurfaceView: " + mSurfaceview.getWidth() + "x" + mSurfaceview.getHeight());

        mSurfaceHolder = mSurfaceview.getHolder(); // 绑定SurfaceView，取得SurfaceHolder对象
        mSurfaceHolder.addCallback(this); // SurfaceHolder加入回调接口
        myBtn01 = findViewById(R.id.btn_connect);
        myBtn02 = findViewById(R.id.btn_trans);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        screenWidth = dm.widthPixels;// 获取屏幕分辨率宽度
        screenHeight = dm.heightPixels;
        // 打印屏幕分辨率
        Log.i(TAG, "屏幕分辨率: " + screenWidth + "x" + screenHeight);

        // 获得输入框的输入
        myEdit01 = findViewById(R.id.input_IP);
        String inputIP = myEdit01.getText().toString();
        if (inputIP.length() > 0 && checkValidIP(inputIP)) {
            serverIP = inputIP;
        }
        // 打印输入框的输入
        Log.i(TAG, "输入框输入的IP: " + inputIP + "serverIP: " + serverIP);

        myBtn01.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isConnecting) {   //如果正在连接中
                    isConnecting = false;
                    isSending = false;
                    myBtn01.setText("开始连接");
                    myBtn02.setText("开始传输");
                    myBtn02.setEnabled(false);
                    try {
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                            socket = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    Log.i(TAG, "myBtn01 断开连接: " + serverIP + ":" + serverPort);
                } else {  //如果没有连接,则开始连接
                    String inputIP = myEdit01.getText().toString();
                    if (inputIP.length() > 0 && checkValidIP(inputIP)) {
                        serverIP = inputIP;
                        // 打印输入框的输入
                        Log.i(TAG, "输入框输入的IP: " + inputIP + "serverIP: " + serverIP);
                    } else if (inputIP.length() > 0 && !checkValidIP(inputIP)) {
                        displayToast("请输入正确的IP地址!");
                        // 打印输入框的输入
                        Log.i(TAG, "输入框输入的IP: " + inputIP + "serverIP: " + serverIP);
                        return;
                    }

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                socket = new Socket(serverIP, serverPort);
                                socket.setSendBufferSize(1024 * 1024 * 2);
                                outputStream = socket.getOutputStream();
                                dos = new DataOutputStream(socket.getOutputStream());
                                //输出缓冲区大小
                                Log.i(TAG, "SendBufferSize(): " + socket.getSendBufferSize() + " ReceiveBufferSize(): " + socket.getReceiveBufferSize());
                                Log.i(TAG, "myBtn01 连接成功: " + serverIP + ":" + serverPort);
                            } catch (IOException e) {
                                Log.e(TAG, "myBtn01 连接失败: " + e.getMessage());
                                e.printStackTrace();
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // TODO Auto-generated method stub
                                    if (socket == null || socket.isClosed()) {
                                        displayToast("连接失败!");
                                        myBtn01.setText("开始连接");
                                        myBtn02.setText("开始传输");
                                    } else {
                                        displayToast("连接成功!");
                                        isConnecting = true;
                                        myBtn01.setText("断开连接");
                                        myBtn02.setEnabled(true);
                                    }
                                }
                            });
                        }
                    }).start();
                }
            } // 创建监听
        });

        myBtn02.setEnabled(false);
        myBtn02.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isSending) {  //如果正在传输中
                    isSending = false;
                    myBtn02.setText("开始传输");
                    displayToast("传输已经停止!");
                    Log.i(TAG, "myBtn02 停止传输: " + serverIP + ":" + serverPort);
                    if (socketThread != null && socketThread.isAlive()) {
                        socketThread.interrupt();
                    }
                    socketThread = null;
                } else {  //如果没有传输,则开始传输
                    isSending = true;
                    myBtn02.setText("停止传输");
                    displayToast("传输已经开始!");
                    Log.i(TAG, "myBtn02 开始传输: " + serverIP + ":" + serverPort);

                    if (socketThread != null && socketThread.isAlive()) {
                        socketThread.interrupt();
                    }
                    socketThread = null;
                    socketThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (isSending) {
                                if (!queue.isEmpty()) {
                                    byte[] imageData = queue.poll();
                                    try {
                                        //获得当前时间
                                        long startTime = System.currentTimeMillis();
                                        // 发送图片大小
                                        //dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                                        Log.i(TAG, "计算时间1: " + (System.currentTimeMillis() - startTime));
                                        dos.writeInt(imageData.length);
                                        Log.i(TAG, "计算时间2: " + (System.currentTimeMillis() - startTime));
                                        dos.flush();
                                        Log.i(TAG, "计算时间3: " + (System.currentTimeMillis() - startTime));
                                        // 发送图片数据
                                        dos.write(imageData);
                                        //outputStream = socket.getOutputStream();
                                        Log.i(TAG, "计算时间4: " + (System.currentTimeMillis() - startTime));

//                                        ByteArrayInputStream inputStream = new ByteArrayInputStream(imageData);
//                                        int amount;
//                                        while ((amount = inputStream.read(byteBuffer)) != -1) {
//                                            outputStream.write(byteBuffer, 0, amount);
//                                        }

                                        //socket.getOutputStream().write(imageData);
                                        //Log.i(TAG, "计算时间5: " + (System.currentTimeMillis() - startTime));
                                        socket.getOutputStream().flush();
                                        dos.flush();
                                        long endTime = System.currentTimeMillis();
                                        Log.d(TAG, "队列长度: " + queue.size() + " 发送图片大小: " + imageData.length + " 发送图片耗时: " + (endTime - startTime) + "ms");
                                        totalSize += imageData.length;
                                        Log.d(TAG, "totalSize:" + totalSize);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            queue.clear();
                        }
                    });
                    socketThread.start();
                }
            } // 创建监听
        });
    }

    @Override // SurfaceView创建时激发
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            initCamera();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override // SurfaceView改变时激发
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

    }

    @SuppressWarnings("deprecation")
    @Override // SurfaceView销毁时激发
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        try {
            if (mCamera != null) {
                mCamera.setPreviewCallback(null); // ！！这个必须在前，不然退出出错
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "surfaceDestroyed Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!isSending) {
            return;
        }
        Camera.Size size = camera.getParameters().getPreviewSize();
        int format = camera.getParameters().getPreviewFormat();
        try {
            if (data != null) {
                //Log.d(TAG, "onPreviewFrame YuvImage width and height:" + size.width + "x" + size.height);
                //new Thread(()-> SyncArea.sendImage(data, format, size, socket)).start();

                try {
                    YuvImage image = new YuvImage(data, format, size.width, size.height, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    // 设置图片质量和尺寸，将NV21格式图片压缩成Jpeg，并得到JPEG数据流
                    if (!image.compressToJpeg(new Rect(0, 0, size.width, size.height), 80, baos)) {
                        Log.e(TAG, "onPreviewFrame Yuv Image compressToJpeg failed");
                        return;
                    }

                    while (queue.size() > 500) {
                        Log.d(TAG, "onPreviewFrame queue size:" + queue.size() + " free memory:" + Runtime.getRuntime().freeMemory() + " total memory:" + Runtime.getRuntime().totalMemory() + " max memory:" + Runtime.getRuntime().maxMemory());
                        sleep(10);
                    }
                    byte[] imageData = baos.toByteArray();
                    queue.add(imageData);
                    Log.d(TAG, "onPreviewFrame jpeg size: " + imageData.length);
                    System.gc();
                } catch (OutOfMemoryError e) {
                    Log.e(TAG, "queue size:" + queue.size());
                    Log.e(TAG, "onPreviewFrame Yuv Image compressToJpeg Error: " + e.getMessage());
                    e.printStackTrace();
                } catch (Exception e) {
                    // 打印错误信息
                    Log.e(TAG, "onPreviewFrame Error: " + e.getMessage());
                    e.printStackTrace();
                }

                /*
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (yuvType == null)
                            {
                                yuvType = new Type.Builder(rs, Element.U8(rs)).setX(data.length);
                                in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

                                rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(size.width).setY(size.height);
                                out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
                            }
                            in.copyFrom(data);
                            yuvToRgbIntrinsic.setInput(in);
                            yuvToRgbIntrinsic.forEach(out);
                            Bitmap bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888);
                            out.copyTo(bitmap);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            // 设置图片质量和尺寸，将NV21格式图片压缩成Jpeg，并得到JPEG数据流
                            if(!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)){
                                Log.e(TAG, "onPreviewFrame: compress yuv to jpg failed!");
                                return;
                            }

                            byte[] imageData = baos.toByteArray();
                            // lock.lock();  //获取锁
                            // 发送图片大小
                            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                            dos.writeInt(imageData.length);
                            dos.flush();
                            // 发送图片数据
                            outputStream = socket.getOutputStream();
                            outputStream.write(imageData);
                            outputStream.flush();
                            baos.flush();
                            baos.close();
                            Log.d(TAG, "onPreviewFrame jpeg size: " + imageData.length);
                        } catch (IOException e) {
                            // 打印错误信息
                            Log.e(TAG, "onPreviewFrame Error: " + e.getMessage());
                            e.printStackTrace();
                        } finally {
                            System.gc();
                            //lock.unlock();  //释放锁
                        }
                    }
                }).start();*/
                /*
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            YuvImage image = new YuvImage(data, format, size.width, size.height, null);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            // 设置图片质量和尺寸，将NV21格式图片压缩成Jpeg，并得到JPEG数据流
                            image.compressToJpeg(new Rect(0, 0, size.width, size.height), 80, baos);

                            byte[] imageData = baos.toByteArray();
                            // 发送图片大小
                            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                            dos.writeInt(imageData.length);
                            dos.flush();
                            // 发送图片数据
                            outputStream = socket.getOutputStream();
                            outputStream.write(imageData);
                            outputStream.flush();
                            baos.flush();
                            baos.close();
                            Log.d(TAG, "onPreviewFrame jpeg size: " + imageData.length);
                        } catch (IOException e) {
                            // 打印错误信息
                            Log.e(TAG, "onPreviewFrame Error: " + e.getMessage());
                            e.printStackTrace();
                            // throw new RuntimeException(e);
                        }
                        //camera.addCallbackBuffer(data);
                    }
                }).start();  */
            }
        } catch (Exception e) {
            Log.e(TAG, "onPreviewFrame Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private void initCamera() throws IOException {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
        Log.i(TAG, "SurfaceView: " + mSurfaceview.getWidth() + "x" + mSurfaceview.getHeight());
        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK); // 打开摄像头
        } catch (Exception e) {
            Log.e(TAG, "initCamera open Error: " + e.getMessage());
            e.printStackTrace();
        }
        Log.i(TAG, "initCamera: " + mCamera + " open the CAMERA_FACING_BACK");
        try {
            Camera.Parameters parameters = mCamera.getParameters(); // 获取各项参数
            parameters.setPreviewSize(640, 480);
            //parameters.setPreviewSize(640,480);
            List<Integer> frameList = parameters.getSupportedPreviewFrameRates(); // 获取支持预览帧率
            List<Camera.Size> sizeList = parameters.getSupportedPreviewSizes(); // 获取支持预览大小
            List<Camera.Size> pictureList = parameters.getSupportedPictureSizes(); // 获取支持保存的图片尺寸
            // 用一行Log输出支持的帧率列表
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < frameList.size(); i++) {
                sb.append(frameList.get(i)).append(" ");
            }
            Log.i(TAG, "initCamera frameList: " + sb.toString());
            // 用一行Log输出支持的预览大小列表
            sb = new StringBuilder();
            for (int i = 0; i < sizeList.size(); i++) {
                sb.append(sizeList.get(i).width).append("x").append(sizeList.get(i).height).append(" ");
            }
            Log.i(TAG, "initCamera sizeList: " + sb.toString());
            // 用一行Log输出支持的保存图片大小列表
            sb = new StringBuilder();
            for (int i = 0; i < pictureList.size(); i++) {
                sb.append(pictureList.get(i).width).append("x").append(pictureList.get(i).height).append(" ");
            }
            Log.i(TAG, "initCamera pictureList: " + sb.toString());

//            Camera.Size optionSize = getOptimalPreviewSize(sizeList, screenWidth, screenHeight); // 获取一个最为适配的预览大小
//            parameters.setPreviewSize(optionSize.width, optionSize.height); // 设置预览大小
            parameters.setPreviewFrameRate(30);
            parameters.setPictureFormat(ImageFormat.NV21); // 设置图片格式
            parameters.setJpegQuality(100); // 设置照片质量
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); // 设置自动对焦
            mCamera.setParameters(parameters); // 设置各项参数

            mCamera.setPreviewDisplay(mSurfaceHolder);
            mCamera.setDisplayOrientation(90); // 设置预览角度
            mCamera.setPreviewCallback(this); // 设置预览回调
            mCamera.startPreview(); // 开始预览
            mCamera.autoFocus(null); // 自动对焦
        } catch (Exception e) {
            Log.e(TAG, "initCamera preview Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private void changeCameraParameters(int width, int height, int frameRate) throws IOException {
        if (mCamera != null) {
            try {
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                Camera.Parameters parameters = mCamera.getParameters(); // 获取各项参数
                parameters.setPreviewSize(width, height);
                parameters.setPreviewFrameRate(frameRate);
                mCamera.setParameters(parameters); // 设置各项参数

                mCamera.setPreviewDisplay(mSurfaceHolder);
                mCamera.setDisplayOrientation(90); // 设置预览角度
                mCamera.setPreviewCallback(this); // 设置预览回调
                mCamera.startPreview(); // 开始预览
            } catch (Exception e) {
                Log.e(TAG, "changeCameraParameters Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizeList, int screenWidth, int screenHeight) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) screenWidth / screenHeight;
        if (sizeList == null) return null;
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        int targetHeight = screenHeight;
        for (Camera.Size size : sizeList) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizeList) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    // 检查IP地址是否合法
    private boolean checkValidIP(String inputIP) {
/*        String ip = "([1-9]|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])(\\.(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])){3}";
        Pattern pattern = Pattern.compile(ip);
        Matcher matcher = pattern.matcher(ipAddress);
        return matcher.matches();*/
        String[] ip = inputIP.split("\\.");
        if (ip.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            int num = Integer.parseInt(ip[i]);
            if (num < 0 || num > 255) {
                return false;
            }
        }
        return true;
    }

    //显示Toast函数
    private void displayToast(String s) {
        //Looper.prepare();
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        //Looper.loop();
    }

    /**
     * 创建菜单
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 0, 0, "系统设置");
        menu.add(0, 1, 1, "关于程序");
        menu.add(0, 2, 2, "退出程序");
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * 菜单选中时发生的相应事件
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);//获取菜单
        switch (item.getItemId())//菜单序号
        {
            case 0://系统设置
            {
                Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                startActivity(intent);
            }
            break;
            case 1://关于程序
            {
                new AlertDialog.Builder(this)
                        .setTitle("关于本程序")
                        .setMessage("本程序默认服务器端口为8888,故可能导致端口冲突;\n本程序由西安交通大学计算机学院徐亮编写。\nEmail：brightxu18@163.com")
                        .setPositiveButton
                                (
                                        "我知道了",
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                            }
                                        }
                                )
                        .show();
            }
            break;
            case 2://退出程序
            {
                //杀掉线程强制退出
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            break;
        }
        return true;
    }

}