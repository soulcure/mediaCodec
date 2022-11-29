package com.coocaa.mediacodec;

import static java.lang.Math.abs;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.coocaa.mediacodec.util.Permission;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

@SuppressWarnings("deprecation")
public class MainActivity5 extends AppCompatActivity {
    private static final String TAG = "yao";
    private static final int MIN_BITRATE_THRESHOLD = 4 * 1024 * 1024;  //bit per second，每秒比特率

    private static final int MAX_VIDEO_FPS = 30;   //frames/sec
    private static final int I_FRAME_INTERVAL = 1;  //关键帧频率，10秒一个关键帧

    private CameraDevice mCameraDevice;
    private MediaCodec mEncoder;

    private LinkedBlockingQueue<FrameInfo> mSendQueue;

    // camera
    private int mCameraId = CameraCharacteristics.LENS_FACING_FRONT; // 要打开的摄像头ID
    private final Size mPreviewSize = new Size(1280, 720); // 固定1280*720演示

    private CameraCaptureSession mCaptureSession;

    // handler
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private DecoderVideo decoderVideo;

    // output
    private ImageReader mImageReader; // 预览回调的接收者

    private SurfaceHolder holder, holder_dec;
    private Camera camera;
    private SurfaceView surfaceView_cam, surfaceView_dec;
    private static final long timeoutUs = 1000 * 1000;//timeoutUs – 以微秒为单位的超时，负超时表示“无限”
    private int mPreviewViewWidth, mPreviewViewHeight;
    private int mWidth, mHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main5);

        mSendQueue = new LinkedBlockingQueue<>();

        initView();
    }

    private void initView() {
        surfaceView_cam = findViewById(R.id.cameraSurface);
        surfaceView_dec = findViewById(R.id.decodeSurface);

        holder = surfaceView_cam.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {

            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.e(TAG, "surfaceView_cam surfaceChanged width:" + width + " height:" + height);
                mPreviewViewWidth = width;
                mPreviewViewHeight = height;

                openCamera();
                startMediaCodecEncoder();
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });

        holder_dec = surfaceView_dec.getHolder();
        holder_dec.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.e(TAG, "surfaceCreated: new H264Decoder");
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.e(TAG, "surfaceView_dec surfaceChanged width:" + width + " height:" + height);
                decoderVideo = new DecoderVideo(holder.getSurface(), width, height, mSendQueue);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.e(TAG, "surfaceDestroyed: ================");
                decoderVideo.close();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Permission.checkPermission(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCodec();
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
                mWidth, mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, MIN_BITRATE_THRESHOLD);//500kbps
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_VIDEO_FPS);

        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar); //COLOR_FormatSurface
        mediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();

    }

    public void stopCodec() {
        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            mEncoder = null;
        }
    }

    private static abstract class ClosestComparator<T> implements Comparator<T> {
        // Difference between supported and requested parameter.
        abstract int diff(T supportedParameter);

        @Override
        public int compare(T t1, T t2) {
            return diff(t1) - diff(t2);
        }
    }

    public static Camera.Size getClosestSupportedSize(
            List<Camera.Size> supportedSizes, final int requestedWidth, final int requestedHeight) {
        return Collections.min(supportedSizes, new ClosestComparator<Camera.Size>() {
            @Override
            int diff(Camera.Size size) {
                return abs(requestedWidth - size.width) + abs(requestedHeight - size.height);
            }
        });
    }

    private static Camera.Size findClosestPictureSize(List<Camera.Size> cameraSizes, int width, int height) {
        return getClosestSupportedSize(cameraSizes, width, height);
    }

    // Convert from android.hardware.Camera.Size to Size.
    static List<Size> convertSizes(List<Camera.Size> cameraSizes) {
        final List<Size> sizes = new ArrayList<Size>();
        for (android.hardware.Camera.Size size : cameraSizes) {
            sizes.add(new Size(size.width, size.height));
        }
        return sizes;
    }

    private void openCamera() {
        camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
//        camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
        //获取相机参数
        Camera.Parameters parameters = camera.getParameters();

        List<Camera.Size> supList = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = getClosestSupportedSize(supList, mPreviewViewWidth, mPreviewViewHeight);
        parameters.setPreviewSize(previewSize.width, previewSize.height);
        Log.e(TAG, "PreView width: " + previewSize.width + ",height: " + previewSize.height);

        List<Camera.Size> picList = parameters.getSupportedPictureSizes();
        Camera.Size pictureSize = getClosestSupportedSize(picList, mPreviewViewWidth, mPreviewViewHeight);
        mWidth = pictureSize.width;
        mHeight = pictureSize.height;
        parameters.setPictureSize(pictureSize.width, pictureSize.height);
        Log.e(TAG, "Picture width: " + previewSize.width + ",height: " + previewSize.height);

        //设置预览格式（也就是每一帧的视频格式）YUV420下的NV21
        parameters.setPreviewFormat(ImageFormat.NV21);
        parameters.setPreviewFpsRange(15, 30);
        //设置预览图像分辨率

        //设置预览图像帧率
        parameters.setPreviewFrameRate(15);
        //相机旋转0度
        camera.setDisplayOrientation(0);
        //配置camera参数
        camera.setParameters(parameters);
        try {
            camera.setPreviewDisplay(holder);
        } catch (IOException e) {
            e.printStackTrace();
        }

        camera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                if (mEncoder != null)
                    encoderH264(data);
            }
        });
        camera.startPreview();
    }


    /**
     * 将NV21编码成H264
     */
    public void encoderH264(byte[] nv21) {
        //将NV21编码成NV12
        byte[] nv12 = NV21ToNV12(nv21, mWidth, mHeight);

        int inputBufferIndex = mEncoder.dequeueInputBuffer(timeoutUs);
        if (inputBufferIndex < 0) {
            Log.e(TAG, "dequeueInputBuffer result error:" + inputBufferIndex);
            return;
        }

        //当输入缓冲区有效时,就是>=0
        ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
        inputBuffer.clear();
        //往输入缓冲区写入数据
        inputBuffer.put(nv12);

        //五个参数，第一个是输入缓冲区的索引，第二个数据是输入缓冲区起始索引，第三个是放入的数据大小，第四个是时间戳，保证递增就是
        mEncoder.queueInputBuffer(inputBufferIndex, 0, nv12.length, System.nanoTime() / 1000, 0);


        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        //拿到输出缓冲区的索引
        int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
        if (outputBufferIndex < 0) {
            Log.e(TAG, "dequeueOutputBuffer result error:" + outputBufferIndex);
            return;
        }

        ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
        byte[] outData = new byte[bufferInfo.size];
        outputBuffer.get(outData);
        ByteBuffer outPutByteBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
        assert outPutByteBuffer != null;
        int flags = bufferInfo.flags;
        if (flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG || flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
            Log.v(TAG, "buffer flags: " + flags);
        }

        mSendQueue.offer(new FrameInfo(bufferInfo, outPutByteBuffer));

        mEncoder.releaseOutputBuffer(outputBufferIndex, false);
    }

    /**
     * 因为从MediaCodec不支持NV21的数据编码，所以需要先讲NV21的数据转码为NV12
     */
    private byte[] NV21ToNV12(byte[] nv21, int width, int height) {
        byte[] nv12 = new byte[width * height * 3 / 2];
        int frameSize = width * height;
        int i, j;
        System.arraycopy(nv21, 0, nv12, 0, frameSize);
        for (i = 0; i < frameSize; i++) {
            nv12[i] = nv21[i];
        }
        for (j = 0; j < frameSize / 2; j += 2) {
            nv12[frameSize + j - 1] = nv21[j + frameSize];
        }
        for (j = 0; j < frameSize / 2; j += 2) {
            nv12[frameSize + j] = nv21[j + frameSize - 1];
        }
        return nv12;
    }


    public void releaseCamera() {
        Log.v(TAG, "releaseCamera");
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        stopBackgroundThread(); // 对应 openCamera() 方法中的 startBackgroundThread()
    }


    public void switchCamera() {
        mCameraId ^= 1;
        Log.d(TAG, "switchCamera: mCameraId: " + mCameraId);
        releaseCamera();
        openCamera();
    }

    private void startBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null) {
            Log.v(TAG, "startBackgroundThread");
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        Log.v(TAG, "stopBackgroundThread");
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


}