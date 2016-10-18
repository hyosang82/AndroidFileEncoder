package kr.hyosang;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Created by hyosang on 2016. 10. 13..
 */

public class FileEncoder extends Thread {
    private ByteArrayOutputStream passingBuffer;

    private int findColorFormatForEncoder(String mime) {
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo [] codecInfos = mediaCodecList.getCodecInfos();

        for(MediaCodecInfo codecInfo : codecInfos) {
            if(codecInfo.isEncoder()) {
                MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mime);
                for(int i=0;i<capabilities.colorFormats.length;i++) {
                    if(isYUV(capabilities.colorFormats[i])) {
                        return capabilities.colorFormats[i];
                    }
                }
            }
        }

        return -1;
    }

    private boolean isYUV(int colorFormat) {
        switch(colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible:
                return true;
        }

        return false;
    }


    @Override
    public void run() {
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource("/mnt/sdcard/APink_Only_One.mp4");

            int trackCount = extractor.getTrackCount();
            String trackMime = null;

            for(int i=0;i<trackCount;i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                Log.d("TEST", String.format("[Track #%d] %s", i, format.getString(MediaFormat.KEY_MIME)));
            }

            MediaFormat sourceFormat = extractor.getTrackFormat(0);
            trackMime = sourceFormat.getString(MediaFormat.KEY_MIME);

            MediaCodec decoder = MediaCodec.createDecoderByType(trackMime);
            decoder.configure(sourceFormat, null, null, 0);

            int colorFormat = 0;
            if(Build.MODEL.startsWith("SHV-E300")) {
                //SHV-E300S / E300K / E300L : Galaxy S4
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            }else if(Build.MODEL.startsWith("SHV-E330")) {
                //SHV-E330S : Galaxy S4 LTE
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            }else {
                colorFormat = findColorFormatForEncoder("video/avc");
            }


            MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat encodeFormat = MediaFormat.createVideoFormat("video/avc", 640, 360);
            encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, 150000);
            encodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            extractor.selectTrack(0);
            ByteBuffer readBuffer;

            int readBufferSize = 1024 * 1024;       //Initial size is 1MB
            int readSize = 0;

            readBuffer = ByteBuffer.allocate(readBufferSize);

            decoder.start();
            encoder.start();

            int decoderInputBufferIndex, decoderOutputBufferIndex;
            int encoderInputBufferIndex, encoderOutputBufferIndex;
            ByteBuffer decoderInputBuffer, decoderOutputBuffer = null;
            ByteBuffer encoderInputBuffer, encoderOutputBuffer = null;
            ByteBuffer passingBuffer = null;

            boolean consumeFileInput = true;
            boolean fileInputComplete = false;
            boolean decoderInputComplete = false;
            boolean encoderInputComplete = false;
            boolean isLocalEOF = false;
            boolean isDecoderOutComplete = false;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            MediaFormat outputBufferFormat;




            while(true) {
                if(!isLocalEOF) {
                    try {
                        readSize = extractor.readSampleData(readBuffer, 0);
                    } catch (IllegalArgumentException e) {
                        Log.e("TEST", "Buffer size not enough. Doubles buffer size from : " + readBufferSize);

                        readBufferSize *= 2;
                        readBuffer = ByteBuffer.allocate(readBufferSize);

                        continue;
                    }

                    if(readSize < 0) {
                        //input end.
                        isLocalEOF = true;
                    }

                    extractor.advance();

                    //Decoder input
                    if(readSize > 0) {
                        decoderInputBufferIndex = decoder.dequeueInputBuffer(-1);
                        if(decoderInputBufferIndex >= 0) {
                            decoderInputBuffer = decoder.getInputBuffer(decoderInputBufferIndex);
                            decoderInputBuffer.put(readBuffer);
                            decoder.queueInputBuffer(decoderInputBufferIndex, 0, readSize, 0, 0);
                        }else {
                            Log.w("TEST", "Decoder input fail!");
                        }
                    }else {
                        Log.d("TEST", "File input EOF");
                        decoder.signalEndOfInputStream();
                    }
                }

                //decoder output
                if(!isDecoderOutComplete) {
                    decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (decoderOutputBufferIndex >= 0) {
                        decoderOutputBuffer = decoder.getOutputBuffer(decoderOutputBufferIndex);

                        //input to encoder
                        encoderInputBufferIndex = encoder.dequeueInputBuffer(-1);
                        if(encoderInputBufferIndex >= 0) {
                            int size = bufferInfo.size;
                            encoderInputBuffer = encoder.getInputBuffer(encoderInputBufferIndex);
                            if(encoderInputBuffer.capacity() >= size) {
                                encoderInputBuffer.put(decoderOutputBuffer);
                            }else {
                                size = 0;
                            }
                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, size, 0, 0);
                        }else {
                            Log.w("TEST", "Encoder input fail!");
                        }

                        decoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
                    }else if(decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d("TEST", "Decoder: Output format changed");
                    }else if(decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

                    }

                    //check if end
                    if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderOutComplete = true;
                    }
                }

                //encoder output
                encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if(encoderOutputBufferIndex >= 0) {
                    encoderOutputBuffer = encoder.getOutputBuffer(encoderOutputBufferIndex);

                    Log.d("TEST", "Encoder output size = " + bufferInfo.size);

                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                }else {
                    Log.w("TEST", "Encoder output fail!");
                }

                if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d("TEST", "Encoder out END");
                    break;
                }
            }

            Log.d("TEST", "Complete");


            extractor.release();
            decoder.stop();
            decoder.release();
            encoder.stop();
            encoder.release();


        }catch(IOException e) {
            e.printStackTrace();
        }

    }
}
