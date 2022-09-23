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

    private TextureView mPreviewView;
    private Handler processHandler;

    private CaptureRequest.Builder mPreviewBuilder;
    private CameraDevice mCameraDevice;
    private Surface mEncoderSurface;
    private MediaCodec mEncoder;
    private DecoderVideo decoderVideo;

    boolean isEncode = false;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private LinkedBlockingQueue<FrameInfo> mSendQueue;

    private int mPreviewViewWidth, mPreviewViewHeight;

    private static final int MIN_BITRATE_THRESHOLD = 4 * 1024 * 1024;  //bit per second，每秒比特率

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
            startMediaCodecEncoder();
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
            Log.i(TAG, "onSurfaceTextureAvailable:  width = " + width + ", height = " + height);
            mPreviewViewWidth = width;
            mPreviewViewHeight = height;
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {

                startMediaCodec();
            }

        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged:  width = " + width + ", height = " + height);
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
            Log.i(TAG, "decodeSurfaceCallBack onSurfaceTextureAvailable:  width = " + width + ", height = " + height);
            decoderVideo = new DecoderVideo(new Surface(surface), width, height, mSendQueue);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "decodeSurfaceCallBack onSurfaceTextureSizeChanged:  width = " + width + ", height = " + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            if (decoderVideo != null) {
                decoderVideo.close();
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

        mPreviewView = findViewById(R.id.cameraSurface);
        mPreviewView.setSurfaceTextureListener(encodeSurfaceCallBack);

        TextureView decodeView = findViewById(R.id.decodeSurface);
        decodeView.setSurfaceTextureListener(decodeSurfaceCallBack);

        //开启线程打印设备硬编硬解的支持参数
        new Thread(this::checkEncoderSupportCodec).start();

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


    /**
     * 检查设备硬编硬解的支持参数
     */
    private void checkEncoderSupportCodec() {
        //获取所有编解码器个数
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecs = list.getCodecInfos();

        //获取所有支持的编解码器信息
        for (MediaCodecInfo codecInfo : codecs) {
            boolean isEncoder = codecInfo.isEncoder();//是否是编码器
            boolean isHardware = codecInfo.isHardwareAccelerated();//是否是硬件支持

            String name = codecInfo.getName();
            // 如果是解码器，判断是否支持Mime类型
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                //H264类型&&硬件类型
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC) && isHardware) {
                    MediaCodecInfo.CodecCapabilities codecCapabilities = codecInfo.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCapabilities = codecCapabilities.getVideoCapabilities();

                    Range<Integer> bitrateRange = videoCapabilities.getBitrateRange();
                    Range<Integer> widthRange = videoCapabilities.getSupportedWidths();
                    Range<Integer> heightRange = videoCapabilities.getSupportedHeights();

                    logParams(false, name, isEncoder, bitrateRange.toString(), widthRange.toString(), heightRange.toString());
                }

                //H265类型&&硬件类型
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


    /**
     * 开始视频编码
     */
    public void startMediaCodecEncoder() {
        try {

            //todo 在此处调试硬件编码器
            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);//使用系统默认H264编码器
            //mEncoder = MediaCodec.createByCodecName("c2.rk.avc.encoder"); // 指定使用rk编码器

        } catch (IOException e) {
            e.printStackTrace();
        }

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mPreviewViewWidth, mPreviewViewHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, MIN_BITRATE_THRESHOLD);//500kbps
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_VIDEO_FPS);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); //COLOR_FormatSurface
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderSurface = mEncoder.createInputSurface();
        //method 1
        mEncoder.setCallback(new EncoderCallback());
        mEncoder.start();
    }

    public void stopCodec() {
        try {
            if (isEncode) {
                isEncode = false;
            } else {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            mEncoder = null;
        }
    }


    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer outPutByteBuffer = mEncoder.getOutputBuffer(index);
            assert outPutByteBuffer != null;
            Log.v(TAG, " outDate.length : " + outPutByteBuffer.remaining());
            mSendQueue.offer(new FrameInfo(info, outPutByteBuffer));

            Log.v(TAG, " mSendQueue.length : " + mSendQueue.size());
            mEncoder.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "Error: " + e);
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