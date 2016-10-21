package kr.hyosang;

import android.util.Log;

/**
 * Created by hyosang on 2016. 10. 16..
 */

public class CircularByteBuffer {
    private byte [] buffer;
    private volatile int readStartIndex = -1;
    private volatile int writeStartIndex = 0;

    public class BufferOverflowException extends Exception {
        private int totalSize = 0;
        private int availSize = 0;
        private int reqSize = 0;

        public BufferOverflowException(int total, int avail, int req) {
            totalSize = total;
            availSize = avail;
            reqSize = req;
        }

        @Override
        public String getMessage() {
            return String.format("Available size is %d (of %d), But trying for %d", availSize, totalSize, reqSize);
        }
    }

    public CircularByteBuffer(int size) {
        buffer = new byte[size];
        readStartIndex = -1;
        writeStartIndex = 0;
    }

    public synchronized int getDataSize() {
        if(readStartIndex < 0) {
            //there is no data.
            return 0;
        }else {
            if(readStartIndex < writeStartIndex) {
                return writeStartIndex - readStartIndex;
            }else if(readStartIndex > writeStartIndex) {
                //circular stored.
                return (buffer.length + writeStartIndex) - readStartIndex;
            }else if(readStartIndex == writeStartIndex) {
                //full
                return buffer.length;
            }
        }

        //can't reach here...
        return 0;
    }

    public int getRemain() {
        return (buffer.length - getDataSize());
    }

    public boolean canStore(int size) {
        return (getRemain() >= size);
    }

    public synchronized void store(byte [] data, int len) throws BufferOverflowException {
        if(canStore(len)) {
            if(readStartIndex == -1) {
                //first write
                System.arraycopy(data, 0, buffer, 0, len);;
                readStartIndex = 0;
                writeStartIndex = len;
            }else {
                if(readStartIndex < writeStartIndex) {
                    int tailEmptyLength = (buffer.length - writeStartIndex);
                    if(tailEmptyLength >= len) {
                        //fill on tail
                        System.arraycopy(data, 0, buffer, writeStartIndex, len);
                        writeStartIndex = writeStartIndex + len;
                    }else {
                        //loop store
                        //1. store in tail
                        System.arraycopy(data, 0, buffer, writeStartIndex, tailEmptyLength);

                        //2. store in head
                        System.arraycopy(data, tailEmptyLength, buffer, 0, len - tailEmptyLength);

                        writeStartIndex = len - tailEmptyLength;
                    }
                }else if(readStartIndex > writeStartIndex) {
                    //loop stored.
                    System.arraycopy(data, 0, buffer, writeStartIndex, len);
                    writeStartIndex += len;
                }else {
                    //readStartIndex == writeStartIndex
                    //can reach here?
                }
            }
        }else {
            //not enough
            throw new BufferOverflowException(buffer.length, getRemain(), len);
        }

        if(writeStartIndex >= buffer.length) {
            writeStartIndex = writeStartIndex % buffer.length;
        }
    }

    public synchronized int obtain(byte [] buf, int len) {
        int obtainSize = Math.min(Math.min(buf.length, len), getDataSize());
        if(obtainSize > 0) {
            if(readStartIndex < writeStartIndex) {
                System.arraycopy(buffer, readStartIndex, buf, 0, obtainSize);
                readStartIndex += obtainSize;
            }else {
                int tailLen = buffer.length - readStartIndex;
                if(obtainSize <= tailLen) {
                    System.arraycopy(buffer, readStartIndex, buf, 0, obtainSize);
                    readStartIndex = (readStartIndex + obtainSize) % buffer.length;
                }else {
                    System.arraycopy(buffer, readStartIndex, buf, 0, tailLen);
                    System.arraycopy(buffer, 0, buf, tailLen, obtainSize - tailLen);
                    readStartIndex = obtainSize - tailLen;
                }
            }

            if(readStartIndex == writeStartIndex) {
                //buffer empty state.
                readStartIndex = -1;
                writeStartIndex = 0;
            }

            return obtainSize;
        }

        return 0;
    }
}
