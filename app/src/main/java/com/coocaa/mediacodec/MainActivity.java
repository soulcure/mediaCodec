package com.coocaa.mediacodec;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.view.View;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "yao";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Thread(this::checkEncoderSupportCodec).start();

        findViewById(R.id.btn_camera).setOnClickListener(this);
        findViewById(R.id.btn_screen_texture).setOnClickListener(this);
        findViewById(R.id.btn_screen_surface).setOnClickListener(this);
        findViewById(R.id.btn_yuv_camera2).setOnClickListener(this);
        findViewById(R.id.btn_yuv_camera1).setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_camera) {
            startActivity(new Intent(this, MainActivity1.class));
        } else if (id == R.id.btn_screen_texture) {
            startActivity(new Intent(this, MainActivity2.class));
        } else if (id == R.id.btn_screen_surface) {
            startActivity(new Intent(this, MainActivity3.class));
        } else if (id == R.id.btn_yuv_camera2) {
            startActivity(new Intent(this, MainActivity4.class));
        } else if (id == R.id.btn_yuv_camera1) {
            startActivity(new Intent(this, MainActivity5.class));
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

}