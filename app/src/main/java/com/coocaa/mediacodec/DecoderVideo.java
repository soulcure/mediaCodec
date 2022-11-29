package com.coocaa.mediacodec;

import static android.media.MediaCodec.INFO_TRY_AGAIN_LATER;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class DecoderVideo {
    private static final String TAG = "yao";

    private final Surface mSurface;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private final LinkedBlockingQueue<FrameInfo> videoList;

    private boolean isExit;

    private MediaCodec mDecoder = null;
    private volatile boolean videoDecoderConfigured = false;

    private static final long timeoutUs = 1000 * 1000;//timeoutUs – 以微秒为单位的超时，负超时表示“无限”

    public DecoderVideo(Surface surface, int width, int height, LinkedBlockingQueue<FrameInfo> videoList) {
        mSurface = surface;
        mFrameWidth = width;
        mFrameHeight = height;
        this.videoList = videoList;

        new Thread(this::videoDecoderInput).start();

        new Thread(this::videoDecoderOutput).start();
    }

    private void initDecoder(MediaCodec.BufferInfo info, ByteBuffer encodedFrame) {
        try {
            String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;  //强制H264
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, mFrameWidth, mFrameHeight);
            format.setByteBuffer("csd-0", encodedFrame);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mFrameWidth * mFrameHeight);

            if (mDecoder == null) {

                // todo 解码器调试修改此处

                mDecoder = MediaCodec.createDecoderByType(mimeType); // 指定系统默认的H264解码器
                // mDecoder = MediaCodec.createByCodecName("OMX.google.h264.decoder");//创建指定google解码器,软解

                // mDecoder = MediaCodec.createByCodecName("OMX.qcom.video.decoder.avc"); // 指定使用高通解码器

                // for RK3588解码器名称
                // mDecoder = MediaCodec.createByCodecName("c2.rk.avc.decoder"); // 指定使用rk解码器

            }
            mDecoder.reset();
            mDecoder.configure(format, mSurface, null, 0);
            mDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);//VIDEO_SCALING_MODE_SCALE_TO_FIT
            mDecoder.start();
            videoDecoderConfigured = true;
            Log.d(TAG, "Decoder codec config success");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "VideoDecoder init error=" + e);
        }

    }


    /**
     * 解码器 input
     */
    private void videoDecoderInput() {
        while (!isExit) {
            try {
                FrameInfo videoFrame = videoList.take();
                ByteBuffer encodedFrames = videoFrame.byteBuffer;
                MediaCodec.BufferInfo info = videoFrame.info;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//配置数据
                    initDecoder(info, encodedFrames);
                    continue;
                }

                //解码 请求一个输入缓存
                int inputBufIndex = mDecoder.dequeueInputBuffer(timeoutUs);
                if (inputBufIndex < 0) {
                    Log.e(TAG, "dequeueInputBuffer result error=" + inputBufIndex);
                    continue;
                }

                ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                inputBuf.clear();
                inputBuf.put(encodedFrames);
                //解码数据添加到输入缓存中
                mDecoder.queueInputBuffer(inputBufIndex, info.offset, info.size, info.presentationTimeUs, info.flags);
                //Log.v(TAG, "end queue input buffer with ts " + info.presentationTimeUs + ",info.size :" + info.size);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "videoDecoderInput error=" + e.getMessage());
            }
        }

        closeDecoder();

    }


    /**
     * 解码器 output
     */
    private void videoDecoderOutput() {
        while (!videoDecoderConfigured) {
            waitTimes();
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!isExit) {
            try {
                if (!videoDecoderConfigured) {
                    continue;
                }
                int result = mDecoder.dequeueOutputBuffer(info, timeoutUs);
                switch (result) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.e(TAG, "解码器超时...");
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.e(TAG, "解码器错误 error=" + result);
                        reformat(mDecoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.e(TAG, "解码器错误 error=" + result);
                        break;
                }

                if (result >= 0) {
                    mDecoder.releaseOutputBuffer(result, true);
                }

            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "解码器异常 exception=" + e.getMessage());
            }
        }

        closeDecoder();
    }

    private static final String MEDIA_FORMAT_KEY_STRIDE = "stride";
    private static final String MEDIA_FORMAT_KEY_SLICE_HEIGHT = "slice-height";
    private static final String MEDIA_FORMAT_KEY_CROP_LEFT = "crop-left";
    private static final String MEDIA_FORMAT_KEY_CROP_RIGHT = "crop-right";
    private static final String MEDIA_FORMAT_KEY_CROP_TOP = "crop-top";
    private static final String MEDIA_FORMAT_KEY_CROP_BOTTOM = "crop-bottom";
    private final Object dimensionLock = new Object();
    private int width;
    private int height;
    private int stride;
    private int sliceHeight;
    // Whether the decoder has finished the first frame.  The codec may not change output dimensions
    // after delivering the first frame.  Only accessed on the output thread while the decoder is
    // running.
    private boolean hasDecodedFirstFrame;
    // Whether the decoder has seen a key frame.  The first frame must be a key frame.  Only accessed
    // on the decoder thread.
    private boolean keyFrameRequired;

    private void reformat(MediaFormat format) {
        Log.d(TAG, "Decoder format changed: " + format.toString());
        final int newWidth;
        final int newHeight;
        if (format.containsKey(MEDIA_FORMAT_KEY_CROP_LEFT)
                && format.containsKey(MEDIA_FORMAT_KEY_CROP_RIGHT)
                && format.containsKey(MEDIA_FORMAT_KEY_CROP_BOTTOM)
                && format.containsKey(MEDIA_FORMAT_KEY_CROP_TOP)) {
            newWidth = 1 + format.getInteger(MEDIA_FORMAT_KEY_CROP_RIGHT)
                    - format.getInteger(MEDIA_FORMAT_KEY_CROP_LEFT);
            newHeight = 1 + format.getInteger(MEDIA_FORMAT_KEY_CROP_BOTTOM)
                    - format.getInteger(MEDIA_FORMAT_KEY_CROP_TOP);
        } else {
            newWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            newHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        }
        // Compare to existing width, height, and save values under the dimension lock.
        synchronized (dimensionLock) {
            if (newWidth != width || newHeight != height) {
                if (hasDecodedFirstFrame) {
                    return;
                } else if (newWidth <= 0 || newHeight <= 0) {
                    Log.w(TAG,
                            "Unexpected format dimensions. Configured " + width + "*" + height + ". "
                                    + "New " + newWidth + "*" + newHeight + ". Skip it");
                    return;
                }
                width = newWidth;
                height = newHeight;
            }
        }

        // Save stride and sliceHeight under the dimension lock.
        synchronized (dimensionLock) {
            if (format.containsKey(MEDIA_FORMAT_KEY_STRIDE)) {
                stride = format.getInteger(MEDIA_FORMAT_KEY_STRIDE);
            }
            if (format.containsKey(MEDIA_FORMAT_KEY_SLICE_HEIGHT)) {
                sliceHeight = format.getInteger(MEDIA_FORMAT_KEY_SLICE_HEIGHT);
            }
            Log.d(TAG, "Frame stride and slice height: " + stride + " x " + sliceHeight);
            stride = Math.max(width, stride);
            sliceHeight = Math.max(height, sliceHeight);
        }
    }

    private synchronized void closeDecoder() {
        try {
            if (mDecoder != null) {
                Log.d(TAG, "unhappy decoder release");
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void waitTimes() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public void close() {
        isExit = true;
        closeDecoder();
    }
}
