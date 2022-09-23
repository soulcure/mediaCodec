package com.coocaa.mediacodec;

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
    private boolean videoDecoderConfigured = false;

    public DecoderVideo(Surface surface, int width, int height, LinkedBlockingQueue<FrameInfo> videoList) {
        mSurface = surface;
        mFrameWidth = width;
        mFrameHeight = height;
        this.videoList = videoList;

        new Thread(this::videoDecoderInput).start();

        new Thread(this::videoDecoderOutput).start();
    }

    private void initDecoder(MediaCodec.BufferInfo info, ByteBuffer encodedFrame) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//配置数据
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
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "VideoDecoder init error=" + e);
            }

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

                initDecoder(info, encodedFrames);

                //解码 请求一个输入缓存
                int inputBufIndex = mDecoder.dequeueInputBuffer(-1);
                if (inputBufIndex < 0) {
                    Log.e(TAG, "dequeueInputBuffer result error---" + inputBufIndex);
                    continue;
                }

                ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                inputBuf.clear();
                inputBuf.put(encodedFrames);
                //解码数据添加到输入缓存中
                mDecoder.queueInputBuffer(inputBufIndex, info.offset, info.size, info.presentationTimeUs, info.flags);
                Log.v(TAG, "end queue input buffer with ts " + info.presentationTimeUs + ",info.size :" + info.size);
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
            waitTimes(10);
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!isExit) {
            try {
                if (!videoDecoderConfigured) {
                    continue;
                }

                int decoderIndex = mDecoder.dequeueOutputBuffer(info, -1);
                if (decoderIndex > 0) {
                    mDecoder.releaseOutputBuffer(decoderIndex, true);
                } else {
                    Log.e(TAG, "videoDecoderOutput dequeueOutputBuffer error=" + decoderIndex);
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "videoDecoderOutput error=" + e.getMessage());
            }
        }

        closeDecoder();
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

    private void waitTimes(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    public void close() {
        isExit = true;
    }
}
