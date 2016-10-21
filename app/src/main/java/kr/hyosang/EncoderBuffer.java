package kr.hyosang;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by hyosang on 2016. 10. 21..
 */

public class EncoderBuffer extends CircularByteBuffer {
    private List<SampleInfo> sampleInfoList;

    public EncoderBuffer(int size) {
        super(size);

        sampleInfoList = new ArrayList<>();
    }

    public static class SampleInfo {
        public long presentationTimeUs = 0;

        public void copyFrom(SampleInfo source) {
            this.presentationTimeUs = source.presentationTimeUs;
        }
    }

    public synchronized void store(byte [] data, int len, long presentationTimeUs) throws BufferOverflowException {
        SampleInfo info = new SampleInfo();
        info.presentationTimeUs = presentationTimeUs;
        sampleInfoList.add(info);

        super.store(data, len);
    }

    @Override
    public synchronized void store(byte[] data, int len) throws BufferOverflowException {
        this.store(data, len, 0);
    }

    public synchronized int obtain(byte [] buf, int len, SampleInfo sampleInfo) {
        if(sampleInfoList.size() > 0) {
            sampleInfo.copyFrom(sampleInfoList.get(0));
            sampleInfoList.remove(0);
        }

        return super.obtain(buf, len);
    }

    @Override
    public synchronized int obtain(byte[] buf, int len) {
        return this.obtain(buf, len, new SampleInfo());
    }
}
