package com.coocaa.mediacodec;

import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity2 extends AppCompatActivity {
    private static final String TAG = "yao";

    private static final int BITRATE = 4 * 1024 * 1024;

    private static final int MAX_VIDEO_FPS = 30;   //frames/sec
    private static final int I_FRAME_INTERVAL = 1;  //关键帧频率，5秒一个关键帧

    private Context mContext;
    private MediaProjection mMediaProjection;

    private MediaProjectionManager projectionManager;
    public static final int REQ_CODE_SHARE_SCREEN = 1002;

    private MediaCodec mEncoder;
    private VirtualDisplay mVirtualDisplay;
    private MediaCodec.BufferInfo mBufferInfo;

    private int mWidth;
    private int mHeight;

    private volatile boolean isExit = false;
    private Thread encoderWorker;

    private LinkedBlockingQueue<FrameInfo> mSendQueue;
    private DecoderVideo decoderVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main2);
        DisplayMetrics dm = new DisplayMetrics();
        Display mDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        mDisplay.getMetrics(dm);
        mWidth = dm.widthPixels;
        mHeight = dm.heightPixels;
        mSendQueue = new LinkedBlockingQueue<>();
        mContext = this;
        projectionManager = (MediaProjectionManager) getApplication().getSystemService(MEDIA_PROJECTION_SERVICE);

        startCapture();

        TextureView decodeView = findViewById(R.id.decodeSurface);
        decodeView.setSurfaceTextureListener(decodeSurfaceCallBack);

        // Locate view
        ImageView imageView = findViewById(R.id.scale_image);
        Animation an = new RotateAnimation(0.0f, 360.0f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        // Set the animation's parameters
        an.setDuration(1000);               // duration in ms
        an.setRepeatCount(0);                // -1 = infinite repeated
        an.setRepeatMode(Animation.REVERSE); // reverses each repeat
        an.setRepeatCount(10);
        an.setFillAfter(true);               // keep rotation after animation
        imageView.setAnimation(an);

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCapture();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_SHARE_SCREEN && resultCode == RESULT_OK) {
            createMediaProjection(resultCode, data);
            isExit = false;
            if (encoderWorker == null) {
                encoderWorker = new Thread(new EncoderWorker(), "Encoder Thread");
            }
            if (!encoderWorker.isAlive()) {
                encoderWorker.start();
            }
        }
    }

    private void stopCapture() {
        if (mVirtualDisplay != null) {
            Log.d(TAG, "virtualDisplay release.................");
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        isExit = true;
        if (mEncoder != null) {
            mEncoder.signalEndOfInputStream();
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }


    private void startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent fgServiceIntent = new Intent(this, MediaProjectFgService.class);
            startForegroundService(fgServiceIntent);
        }

        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQ_CODE_SHARE_SCREEN);
    }


    private void createMediaProjection(int resultCode, Intent data) {
        if (resultCode == -10 || data == null)
            return;

        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager)
                mContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mMediaProjection == null) {
            mMediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        }
    }


    public void createMediaCodec() throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, mWidth, mHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE); //设置比特率

        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_VIDEO_FPS);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);//将一个android surface进行mediaCodec编码
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Log.d(TAG, "createDisplaySurface:" + mWidth + "x" + mHeight + "---mimeType:"
                + mimeType);
    }


    public void createDisplayManager() {
        if (mEncoder == null)
            return;

        Log.d(TAG, "startDisplayManager: create virtualDisplay by mediaProjection");
        mVirtualDisplay = mMediaProjection
                .createVirtualDisplay(
                        "Screen Mirror", mWidth, mHeight,
                        50,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mEncoder.createInputSurface(),
                        null, null);// bsp

    }


    private void doEncodeWork() {
        try {
            //单位微秒 1秒
            long timeoutUs = 2000 * 1000;
            int index = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { //无推流数据
                Log.e(TAG, "MediaCodec INFO_TRY_AGAIN_LATER---");
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.e(TAG, "MediaCodec INFO_OUTPUT_FORMAT_CHANGED---");
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Log.e(TAG, "MediaCodec INFO_OUTPUT_BUFFERS_CHANGED---");
            } else if (index >= 0) {
                //获取数据
                ByteBuffer outPutByteBuffer = mEncoder.getOutputBuffer(index);
                assert outPutByteBuffer != null;
                Log.v(TAG, " outDate.length : " + outPutByteBuffer.remaining());
                mSendQueue.offer(new FrameInfo(mBufferInfo, outPutByteBuffer));

                Log.v(TAG, " mSendQueue.length : " + mSendQueue.size());
                //释放
                mEncoder.releaseOutputBuffer(index, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "doEncodeWork error---");
        }
    }


    private class EncoderWorker implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "EncoderWorker:start WatchDogThread");
            try {
                createMediaCodec();
                createDisplayManager();
                if (mEncoder != null)
                    mEncoder.start();
            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "startDisplayManager: create virtualDisplay error");
            }
            // 创建BufferedOutputStream对象
            while (!isExit) {
                doEncodeWork();
            }
            Log.d(TAG, "EncoderWorker exit");
        }
    }


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


}
