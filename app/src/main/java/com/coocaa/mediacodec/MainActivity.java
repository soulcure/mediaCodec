package com.coocaa.mediacodec;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "yao";


    Handler processHandler, mainHandler;
    TextureView mPreviewView, mDecodeView;

    CameraCaptureSession mSession;
    CaptureRequest.Builder mPreviewBuilder;
    CameraDevice mCameraDevice;
    Surface mEncoderSurface;
    private MediaCodec mCodec;
    DecoderVideo decoder;


    boolean isEncode = false;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private LinkedBlockingQueue<FrameInfo> mSendQueue;


    int mPreviewViewWidth, mPreviewViewHeight;

    private static final int MIN_BITRATE_THRESHOLD = 4 * 1024 * 1024;  //bit per second，每秒比特率
    private static final int DEFAULT_BITRATE = 6 * 1024 * 1024;
    private static final int MAX_BITRATE_THRESHOLD = 8 * 1024 * 1024;

    private static final int MAX_VIDEO_FPS = 60;   //frames/sec
    private static final int I_FRAME_INTERVAL = 10;  //关键帧频率，10秒一个关键帧

    public static final int REQUEST_CODE = 5;
    //定义权限
    private static final String[] permission = new String[]{
            Manifest.permission.CAMERA,
            //Manifest.permission.READ_EXTERNAL_STORAGE,
            //Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };


    ///为了使照片竖直显示
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }


    private void startMediaCodec() {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] CameraIdList = cameraManager.getCameraIdList();
            //获取可用相机设备列表
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(CameraIdList[0]);
            //在这里可以通过CameraCharacteristics设置相机的功能,当然必须检查是否支持
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            //就像这样
            startCodec();
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraManager.openCamera(CameraIdList[0], mCameraDeviceStateCallback, processHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final TextureView.SurfaceTextureListener encodeSurfaceCallBack = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            Log.e(TAG, "onSurfaceTextureAvailable:  width = " + width + ", height = " + height);
            mPreviewViewWidth = width;
            mPreviewViewHeight = height;
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {

                startMediaCodec();
            }

        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.e(TAG, "onSurfaceTextureSizeChanged:  width = " + width + ", height = " + height);
            mPreviewViewWidth = width;
            mPreviewViewHeight = height;
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            stopCodec();
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }

    };


    private final TextureView.SurfaceTextureListener decodeSurfaceCallBack = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            Log.e(TAG, "decodeSurfaceCallBack onSurfaceTextureAvailable:  width = " + width + ", height = " + height);
            decoder = new DecoderVideo(new Surface(surface), width, 720, mSendQueue);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.e(TAG, "decodeSurfaceCallBack onSurfaceTextureSizeChanged:  width = " + width + ", height = " + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            if (decoder != null) {
                decoder.close();
            }
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }

    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        mSendQueue = new LinkedBlockingQueue<>();

        HandlerThread handlerThread = new HandlerThread("CAMERA2");
        handlerThread.start();

        processHandler = new Handler(handlerThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        mPreviewView = findViewById(R.id.cameraSurface);
        mPreviewView.setSurfaceTextureListener(encodeSurfaceCallBack);


        mDecodeView = findViewById(R.id.decodeSurface);
        mDecodeView.setSurfaceTextureListener(decodeSurfaceCallBack);


        new Thread(new Runnable() {
            @Override
            public void run() {
                checkEncoderSupportCodec();
            }
        }).start();


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permission, REQUEST_CODE);
        }


    }


    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMediaCodec();
            } else {
                this.finish();
            }
        }
    }


    private void checkEncoderSupportCodec() {
        //获取所有编解码器个数
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecs = list.getCodecInfos();

        for (MediaCodecInfo codecInfo : codecs) {
            //获取所有支持的编解码器信息

            // 判断是否为编码器，否则直接进入下一次循环
            boolean isEncoder = codecInfo.isEncoder();
            boolean isHardware = codecInfo.isHardwareAccelerated();

            String name = codecInfo.getName();
            // 如果是解码器，判断是否支持Mime类型
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC) && isHardware) {
                    MediaCodecInfo.CodecCapabilities codecCapabilities = codecInfo.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCapabilities = codecCapabilities.getVideoCapabilities();

                    Range<Integer> bitrateRange = videoCapabilities.getBitrateRange();
                    Range<Integer> widthRange = videoCapabilities.getSupportedWidths();
                    Range<Integer> heightRange = videoCapabilities.getSupportedHeights();

                    logParams(false, name, isEncoder, bitrateRange.toString(), widthRange.toString(), heightRange.toString());
                }


                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC) && isHardware) {
                    MediaCodecInfo.CodecCapabilities codecCapabilities = codecInfo.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCapabilities = codecCapabilities.getVideoCapabilities();

                    Range<Integer> bitrateRange = videoCapabilities.getBitrateRange();
                    Range<Integer> heightRange = videoCapabilities.getSupportedHeights();
                    Range<Integer> widthRange = videoCapabilities.getSupportedWidths();

                    logParams(true, name, isEncoder, bitrateRange.toString(), widthRange.toString(), heightRange.toString());
                }
            }
        }

    }


    private void logParams(boolean isH265, String name, boolean isEncode, String bitrateRange, String width, String height) {
        String str;

        if (isH265) {
            str = "H265";
        } else {
            str = "H264";
        }

        if (isEncode) {
            str = str + "硬件编码器：" + name + " 码率：" + bitrateRange + " 视频width范围：" + width + " 视频height范围：" + height;
        } else {
            str = str + "硬件解码器：" + name + " 码率：" + bitrateRange + " 视频width范围：" + width + " 视频height范围" + height;
        }

        Log.d(TAG, str);
    }


    public void startCodec() {
        try {
            mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        } catch (IOException e) {
            e.printStackTrace();
        }

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mPreviewViewWidth, 720);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, MIN_BITRATE_THRESHOLD);//500kbps
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_VIDEO_FPS);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); //COLOR_FormatSurface
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        mCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderSurface = mCodec.createInputSurface();
        //method 1
        mCodec.setCallback(new EncoderCallback());
        mCodec.start();
    }

    public void stopCodec() {
        try {
            if (isEncode) {
                isEncode = false;
            } else {
                mCodec.stop();
                mCodec.release();
                mCodec = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            mCodec = null;
        }
    }


    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer outPutByteBuffer = mCodec.getOutputBuffer(index);
            assert outPutByteBuffer != null;
            Log.d(TAG, " outDate.length : " + outPutByteBuffer.remaining());
            mSendQueue.offer(new FrameInfo(info, outPutByteBuffer));

            Log.d(TAG, " mSendQueue.length : " + mSendQueue.size());
            mCodec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, "Error: " + e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            Log.d(TAG, "encoder output format changed: " + format);
        }
    }


    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            try {
                Log.i(TAG, "CameraDevice.StateCallback  onOpened");
                mCameraDevice = camera;
                startPreview(camera);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (null != mCameraDevice) {
                mCameraDevice.close();
                MainActivity.this.mCameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
        }
    };


    private void startPreview(CameraDevice camera) throws CameraAccessException {
        SurfaceTexture texture = mPreviewView.getSurfaceTexture();
        if (texture == null) {
            return;
        }
        Log.i(TAG, "startPreview");
        texture.setDefaultBufferSize(mPreviewViewWidth, mPreviewViewHeight);
        Surface surface = new Surface(texture);
        try {
            mPreviewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); //CameraDevice.TEMPLATE_STILL_CAPTURE
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mPreviewBuilder.addTarget(surface);
        mPreviewBuilder.addTarget(mEncoderSurface);

        //对于拍照而言，有两个输出流：一个用于预览、一个用于拍照。
        //对于录制视频而言，有两个输出流：一个用于预览、一个用于录制视频。
        camera.createCaptureSession(Arrays.asList(surface, mEncoderSurface), mSessionStateCallback, processHandler);
    }


    private final CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            try {
                Log.i(TAG, "onConfigured");
                mSession = session;
                // 自动对焦
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // 打开闪光灯
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                mPreviewBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
                session.setRepeatingRequest(mPreviewBuilder.build(), null, processHandler); //null
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }
    };


}