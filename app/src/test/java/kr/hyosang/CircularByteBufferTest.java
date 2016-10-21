package kr.hyosang;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Created by hyosang on 2016. 10. 16..
 */

public class CircularByteBufferTest {
    @Test
    public void bufferTest() {
        CircularByteBuffer buffer = new CircularByteBuffer(1024 * 10);
        assertEquals(1024 * 10, buffer.getRemain());

        byte [] test = new byte[1024];
        for(int i=0;i<test.length;i++) {
            test[i] = (byte) i;
        }

        int remains = buffer.getRemain();
        List<Integer> sizeList = new ArrayList<Integer>();

        try {
            for (int i = 0; i < 1000; i++) {
                int loop = (int) (Math.random() * 256.0f);

                buffer.store(test, loop);
                remains -= loop;
                assertEquals(remains, buffer.getRemain());

                sizeList.add(loop);

                System.out.println("STORE " + loop + ", Remain = " + remains);
            }
        }catch(CircularByteBuffer.BufferOverflowException e) {
            System.out.println("FULL : " + e.getMessage());
        }

        for( ; sizeList.size() > 0 ; ) {
            int thisSize = sizeList.get(0);

            buffer.obtain(test, thisSize);

            System.out.println("OBTAIN " + thisSize + ", Remain = " + buffer.getRemain());

            sizeList.remove(0);

            for(int i=0;i<thisSize;i++) {
                assertEquals(i, (0x000000FF & test[i]));
            }
        }
    }
}
