package kr.hyosang;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by hyosang on 2016. 10. 13..
 */

public class FileEncoder extends Thread {
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

    private MediaExtractor mExtractor;
    private EncoderBuffer mPassingBuffer;
    private Object mDecoderTrigger = new Object();
    private Object mEncoderTrigger = new Object();
    private MediaMuxer mMuxer;
    private int mMuxerTrack = -1;

    private Decoder mDecoder = null;
    private EncoderThread mEncoder = null;


    @Override
    public void run() {
        try {
            mExtractor = new MediaExtractor();
            mExtractor.setDataSource("/mnt/sdcard/APink_Only_One.mp4");

            int trackCount = mExtractor.getTrackCount();
            String trackMime = null;

            for(int i=0;i<trackCount;i++) {
                MediaFormat format = mExtractor.getTrackFormat(i);
                Log.d("TEST", String.format("[Track #%d] %s", i, format.getString(MediaFormat.KEY_MIME)));
            }

            MediaFormat sourceFormat = mExtractor.getTrackFormat(0);
            trackMime = sourceFormat.getString(MediaFormat.KEY_MIME);

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


            mMuxer = new MediaMuxer("/mnt/sdcard/encoded.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat encoderFormat = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 150000);
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

            //mMuxerTrack = mMuxer.addTrack(encoderFormat);

            //mMuxer.start();

            mPassingBuffer = new EncoderBuffer(10 * 1024 * 1024);
            mDecoder = new Decoder();
            mEncoder = new EncoderThread();

            mDecoder.start();
            mEncoder.start();



        }catch(IOException e) {
            e.printStackTrace();
        }

    }

    private boolean isDecoderRunning() {
        if(mDecoder != null) {
            Log.d("TEST", "Decoder running? " + mDecoder.isAlive());
            return mDecoder.isAlive();
        }

        return false;
    }

    private class Decoder extends Thread {
        @Override
        public void run() {
            try {
                MediaCodec decoder = MediaCodec.createDecoderByType("video/avc");
                MediaFormat sourceFormat = mExtractor.getTrackFormat(0);
                decoder.configure(sourceFormat, null, null, 0);

                mExtractor.selectTrack(0);

                decoder.start();

                boolean isInputEnd = false;
                boolean isOutputEnd = false;
                int readSize;
                int bufferSize = 1024 * 1024;
                ByteBuffer inputBuffer = ByteBuffer.allocate(bufferSize);
                int decoderInputBufferIndex, decoderOutputBufferIndex;
                ByteBuffer decoderInputBuffer, decoderOutputBuffer;
                int totalRead = 0;
                byte [] buf = new byte[2 * 1024 * 1024];
                long ts;

                do {
                    if(!isInputEnd) {
                        try {
                            readSize = mExtractor.readSampleData(inputBuffer, 0);
                            ts = mExtractor.getSampleTime();
                        } catch (IllegalArgumentException e) {
                            Log.e("TEST", "Buffer size is not enough. Expand buffer from : " + bufferSize);

                            bufferSize *= 2;
                            inputBuffer = ByteBuffer.allocate(bufferSize);

                            //next loop
                            continue;
                        }

                        mExtractor.advance();

                        if (readSize > 0) {
                            totalRead += readSize;

                            decoderInputBufferIndex = decoder.dequeueInputBuffer(20_000);
                            if (decoderInputBufferIndex >= 0) {
                                decoderInputBuffer = decoder.getInputBuffer(decoderInputBufferIndex);
                                decoderInputBuffer.put(inputBuffer);
                                decoder.queueInputBuffer(decoderInputBufferIndex, 0, readSize, ts, 0);

                            } else {
                                Log.e("TEST", "Decoder input error = " + decoderInputBufferIndex);
                            }
                        } else if (readSize < 0) {
                            //EOF
                            Log.d("TEST", "File input EOF");

                            decoderInputBufferIndex = decoder.dequeueInputBuffer(20_000);
                            if(decoderInputBufferIndex >= 0) {
                                decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isInputEnd = true;
                            }else {
                                //TODO here...
                            }
                        } else {
                            Log.d("TEST", "Read size 0");
                        }
                    }

                    if(!isOutputEnd) {
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        decoderOutputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 20_000);
                        if(decoderOutputBufferIndex >= 0) {
                            decoderOutputBuffer = decoder.getOutputBuffer(decoderOutputBufferIndex);
                            decoderOutputBuffer.get(buf, bufferInfo.offset, bufferInfo.size);

                            MediaFormat format = decoder.getOutputFormat(decoderOutputBufferIndex);
                            Log.d("TEST", "Color format = " + format.getInteger(MediaFormat.KEY_COLOR_FORMAT));

                            //consume output
                            while(!mPassingBuffer.canStore(bufferInfo.size)) {
                                synchronized(mDecoderTrigger) {
                                    //Log.d("TEST", "Waiting for buffer...");
                                    Log.d("TEST", "locked");
                                    mDecoderTrigger.wait();
                                    Log.d("TEST", "unlocked");
                                }
                            }
                            mPassingBuffer.store(buf, bufferInfo.size, bufferInfo.presentationTimeUs);

                            Log.d("TEST", "Decodded : " + totalRead + "/ " + bufferInfo.offset + " ~ " + bufferInfo.size + ", " + bufferInfo.presentationTimeUs);

                            if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                //decoder output end.
                                isOutputEnd = true;
                                Log.d("TEST", "Decoder output END");

                            }

                            decoder.releaseOutputBuffer(decoderOutputBufferIndex, false);

                            synchronized(mEncoderTrigger) {
                                mEncoderTrigger.notifyAll();
                            }
                        }
                    }
                }while(!(isInputEnd && isOutputEnd));

                Log.d("TEST", "Decoder complete!");

                decoder.stop();
                decoder.release();
            }catch(IOException e) {
                e.printStackTrace();
            }catch(CircularByteBuffer.BufferOverflowException e) {
                e.printStackTrace();
            }catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class EncoderThread extends Thread {
        @Override
        public void run() {
            try {
                MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
                MediaFormat encoderFormat = MediaFormat.createVideoFormat("video/avc", 1280, 720);
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 150000);
                encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                encoder.start();

                synchronized(mEncoderTrigger) {
                    mEncoderTrigger.wait();
                }

                int encoderInputBufferIndex, encoderOutputBufferIndex;
                ByteBuffer encoderInputBuffer, encoderOutputBuffer;
                int size;
                byte [] buf = new byte[2 * 1024 * 1024];
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                long ts = 0;
                EncoderBuffer.SampleInfo sampleInfo = new EncoderBuffer.SampleInfo();

                while(true) {
                    if(mPassingBuffer.getDataSize() > 0) {
                        encoderInputBufferIndex = encoder.dequeueInputBuffer(20_000);
                        if(encoderInputBufferIndex >= 0) {
                            encoderInputBuffer = encoder.getInputBuffer(encoderInputBufferIndex);
                            size = Math.min(buf.length, Math.min(mPassingBuffer.getRemain(), encoderInputBuffer.capacity()));
                            mPassingBuffer.obtain(buf, size, sampleInfo);
                            encoderInputBuffer.put(buf, 0, size);
                            encoder.queueInputBuffer(encoderInputBufferIndex, 0, size, sampleInfo.presentationTimeUs, 0);

                            //consumed passingbuffer
                            synchronized(mDecoderTrigger) {
                                mDecoderTrigger.notifyAll();
                            }
                        }else {
                            Log.e("TEST", "Encoder input fail : " + encoderInputBufferIndex);
                        }
                    }else {
                        if(!isDecoderRunning()) {
                            //decoder completed.
                            encoderInputBufferIndex = encoder.dequeueInputBuffer(20_000);
                            if(encoderInputBufferIndex >= 0) {
                                encoder.getInputBuffer(encoderInputBufferIndex);
                                encoder.queueInputBuffer(encoderInputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                                Log.d("TEST", "Encoder input END");
                            }
                        }
                    }

                    //encoder output
                    encoderOutputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 20_000);
                    if(encoderOutputBufferIndex >= 0) {
                        encoderOutputBuffer = encoder.getOutputBuffer(encoderOutputBufferIndex);

                        //consume encoder output
                        Log.d("TEST", "Encoder out : " + bufferInfo.size);

                        if(mMuxerTrack >= 0 && bufferInfo.presentationTimeUs > 0) {
                            mMuxer.writeSampleData(mMuxerTrack, encoderOutputBuffer, bufferInfo);
                        }

                        encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                    }else if(encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat fmt = encoder.getOutputFormat();
                        mMuxerTrack = mMuxer.addTrack(fmt);
                        mMuxer.start();
                    }else {
                        Log.e("TEST", "Encoder get fail : " + encoderOutputBufferIndex);
                    }

                    if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("TEST", "Encoder END");
                        break;
                    }
                }

                encoder.stop();
                encoder.release();
            }catch(IOException e) {
                e.printStackTrace();
            }catch(InterruptedException e) {
                e.printStackTrace();
            }

            Log.d("TEST", "Encoder Complete");

            mMuxer.stop();
            mMuxer.release();
        }


    }
}
