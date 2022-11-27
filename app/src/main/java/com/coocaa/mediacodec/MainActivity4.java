package com.coocaa.mediacodec;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageProxy;

import com.coocaa.mediacodec.util.Permission;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;


public class MainActivity4 extends AppCompatActivity {
    private static final String TAG = "yao";
    private static final int MIN_BITRATE_THRESHOLD = 4 * 1024 * 1024;  //bit per second，每秒比特率

    private static final int MAX_VIDEO_FPS = 30;   //frames/sec
    private static final int I_FRAME_INTERVAL = 10;  //关键帧频率，10秒一个关键帧

    private TextureView mPreviewView;

    private CameraDevice mCameraDevice;
    private MediaCodec mEncoder;
    private DecoderVideo decoderVideo;

    boolean isEncode = false;

    private LinkedBlockingQueue<FrameInfo> mSendQueue;

    // camera
    private int mCameraId = CameraCharacteristics.LENS_FACING_FRONT; // 要打开的摄像头ID
//    private final Size mPreviewSize = new Size(1280, 720); // 固定1280*720演示

    private int mWidth;
    private int mHeight;
    private Context mContext;

    private CameraCaptureSession mCaptureSession;

    // handler
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    // output
    private Surface mPreviewSurface; // 输出到屏幕的预览
    private ImageReader mImageReader; // 预览回调的接收者

    private static final long timeoutUs = 1000 * 1000;//timeoutUs – 以微秒为单位的超时，负超时表示“无限”


    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) return;

                if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length != 3) {
                    Log.e(TAG, "Unexpected image format: " +
                            image.getFormat() + " or #planes: " +
                            image.getPlanes().length);
                    throw new IllegalStateException();
                }
                encoderH264(image);
            } catch (IllegalStateException ex) {
                Log.e(TAG, "acquireLatestImage():");
            }
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


    private final TextureView.SurfaceTextureListener previewSurfaceCallBack = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "onSurfaceTextureAvailable:  width = " + width + ", height = " + height);
            // mPreviewSize必须先初始化完成
            surface.setDefaultBufferSize(width, height);
            mWidth = width;
            mHeight = height;
            openCamera();
            mPreviewSurface = new Surface(surface);
            startMediaCodecEncoder();
            initPreviewRequest();

        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged:  width = " + width + ", height = " + height);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        mContext = this;
        setContentView(R.layout.activity_main4);
//        DisplayMetrics dm = new DisplayMetrics();
//        Display mDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
//                .getDefaultDisplay();
//        mDisplay.getMetrics(dm);
//        mWidth = dm.widthPixels / 2;
//        mHeight = dm.heightPixels;

        mSendQueue = new LinkedBlockingQueue<>();

        mPreviewView = findViewById(R.id.cameraSurface);
        mPreviewView.setSurfaceTextureListener(previewSurfaceCallBack);

        TextureView decodeView = findViewById(R.id.decodeSurface);
        decodeView.setSurfaceTextureListener(decodeSurfaceCallBack);

        //开启线程打印设备硬编硬解的支持参数
        new Thread(this::checkEncoderSupportCodec).start();
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
    protected void onStop() {
        super.onStop();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCodec();
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
                    int maxSupportedInstances = codecInfo.getCapabilitiesForType(type).getMaxSupportedInstances();

                    MediaCodecInfo.CodecCapabilities codecCapabilities = codecInfo.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCapabilities = codecCapabilities.getVideoCapabilities();

                    Range<Integer> bitrateRange = videoCapabilities.getBitrateRange();
                    Range<Integer> widthRange = videoCapabilities.getSupportedWidths();
                    Range<Integer> heightRange = videoCapabilities.getSupportedHeights();

                    logParams(false, name, isEncoder, bitrateRange.toString(), widthRange.toString(), heightRange.toString(), maxSupportedInstances);
                }

                //H265类型&&硬件类型
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC) && isHardware) {
                    int maxSupportedInstances = codecInfo.getCapabilitiesForType(type).getMaxSupportedInstances();

                    MediaCodecInfo.CodecCapabilities codecCapabilities = codecInfo.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCapabilities = codecCapabilities.getVideoCapabilities();

                    Range<Integer> bitrateRange = videoCapabilities.getBitrateRange();
                    Range<Integer> heightRange = videoCapabilities.getSupportedHeights();
                    Range<Integer> widthRange = videoCapabilities.getSupportedWidths();

                    logParams(true, name, isEncoder, bitrateRange.toString(), widthRange.toString(), heightRange.toString(), maxSupportedInstances);
                }
            }
        }

    }


    private void logParams(boolean isH265, String name, boolean isEncode,
                           String bitrateRange, String width, String height, int maxSupportedInstances) {
        String str;

        if (isH265) {
            str = "H265";
        } else {
            str = "H264";
        }

        if (isEncode) {
            str = str + "硬件编码器：" + name + " 码率：" + bitrateRange + " 视频width范围：" + width + " 视频height范围：" + height + " 最大实例数：" + maxSupportedInstances;
        } else {
            str = str + "硬件解码器：" + name + " 码率：" + bitrateRange + " 视频width范围：" + width + " 视频height范围" + height + " 最大实例数：" + maxSupportedInstances;
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
                mWidth, mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, MIN_BITRATE_THRESHOLD);//500kbps
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_VIDEO_FPS);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible); //COLOR_FormatSurface
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


    /**
     * 打开摄像头的回调
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "onOpened");
            mCameraDevice = camera;
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "onDisconnected");
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera Open failed, error: " + error);
            releaseCamera();
        }
    };


    @SuppressLint("MissingPermission")
    public void openCamera() {
        Log.v(TAG, "openCamera");
        startBackgroundThread(); // 对应 releaseCamera() 方法中的 stopBackgroundThread()
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            mImageReader = ImageReader.newInstance(mWidth, mHeight, ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
            // 打开摄像头
            cameraManager.openCamera(Integer.toString(mCameraId), mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * 将NV21编码成H264
     */
    public void encoderH264(Image image) {
        //将NV21编码成NV12
        byte[] nv21 = YUV_420_888toNV21(image);
        //byte[] nv12 = NV21ToNV12(nv21, mWidth, mHeight);
        byte[] nv12 = nv21;

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

        //Log.v(TAG, " outDate.length : " + outPutByteBuffer.remaining());
        mSendQueue.offer(new FrameInfo(bufferInfo, outPutByteBuffer));

        mEncoder.releaseOutputBuffer(outputBufferIndex, false);
    }

    private byte[] YUV_420_888toNV21_new(Image image) {
//        int width = image.getWidth();
//        int height = image.getHeight();

        int width = mWidth;
        int height = mHeight;
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] mBuffer = new byte[ySize + uvSize * 2];


        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert (image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(mBuffer, 0, ySize);
            pos += ySize;
        } else {
            int yBufferPos = width - rowStride; // not an actual position
            for (; pos < ySize; pos += width) {
                yBufferPos += rowStride - width;
                yBuffer.position(yBufferPos);
                yBuffer.get(mBuffer, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert (rowStride == image.getPlanes()[1].getRowStride());
        assert (pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            vBuffer.put(1, (byte) 0);
            if (uBuffer.get(0) == 0) {
                vBuffer.put(1, (byte) 255);
                if (uBuffer.get(0) == 255) {
                    vBuffer.put(1, savePixel);
                    vBuffer.get(mBuffer, ySize, uvSize);

                    return mBuffer; // shortcut
                }
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                mBuffer[pos++] = vBuffer.get(vuPos);
                mBuffer[pos++] = uBuffer.get(vuPos);
            }
        }

        return mBuffer;
    }


    /**
     * 将NV21格式数据转换为NV12格式数据
     * NV12与NV21类似，U 和 V 交错排列,不同在于UV顺序。
     * NV12: YYYYYYYY UVUV =>YUV420SP
     * NV21: YYYYYYYY VUVU =>YUV420SP
     */
    private void NV21ToNV12(byte[] nv21, byte[] nv12, int width, int height) {

        if (nv21 == null || nv12 == null) return;
        int frameSize = width * height;
        int i = 0, j = 0;
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


    private byte[] YUV_420_888toNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int ySize = width * height;
        int uvSize = width * height / 4;

        byte[] bytes = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert (image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(bytes, 0, ySize);
            pos += ySize;
        } else {
            int yBufferPos = width - rowStride; // not an actual position
            for (; pos < ySize; pos += width) {
                yBufferPos += rowStride - width;
                yBuffer.position(yBufferPos);
                yBuffer.get(bytes, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert (rowStride == image.getPlanes()[1].getRowStride());
        assert (pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            vBuffer.put(1, (byte) 0);
            if (uBuffer.get(0) == 0) {
                vBuffer.put(1, (byte) 255);
                uBuffer.get(0);
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                bytes[pos++] = vBuffer.get(vuPos);
                bytes[pos++] = uBuffer.get(vuPos);
            }
        }

        return bytes;
    }


    /**
     * YUV_420_888转NV21
     *
     * @param image CameraX ImageProxy
     * @return byte array
     */
    public static byte[] yuv420ToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        int size = image.getWidth() * image.getHeight();
        byte[] nv21 = new byte[size * 3 / 2];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);

        byte[] u = new byte[uSize];
        uBuffer.get(u);

        //每隔开一位替换V，达到VU交替
        int pos = ySize + 1;
        for (int i = 0; i < uSize; i++) {
            if (i % 2 == 0) {
                nv21[pos] = u[i];
                pos += 2;
            }
        }
        return nv21;
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


    private void initPreviewRequest() {
        try {
            final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 添加输出到屏幕的surface
            builder.addTarget(mPreviewSurface);
            // 添加输出到ImageReader的surface。然后我们就可以从ImageReader中获取预览数据了
            builder.addTarget(mImageReader.getSurface());
            mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            // 设置连续自动对焦和自动曝光
                            builder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            builder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                            CaptureRequest captureRequest = builder.build();
                            try {
                                // 一直发送预览请求
                                mCaptureSession.setRepeatingRequest(captureRequest, null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                                Log.e(TAG, "4567 error=" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "ConfigureFailed. session: mCaptureSession");
                        }
                    }, mBackgroundHandler); // handle 传入 null 表示使用当前线程的 Looper
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Log.e(TAG, "88 error=" + e.getMessage());
        }
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