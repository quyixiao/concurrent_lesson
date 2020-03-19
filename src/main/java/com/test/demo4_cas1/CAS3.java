package com.test.demo4_cas1;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicStampedReference;

public class CAS3 {

    private static AtomicStampedReference asr;

    static {
        CAS3.asr = new AtomicStampedReference(100, 1);
    }

    public static void main(String[] args) {
        final Thread tf1 = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(2L);
            } catch (InterruptedException e3) {
                e3.printStackTrace();
            }
            System.out.println("1111111111111111======" + CAS3.asr.getStamp());
            System.out.println(CAS3.asr.compareAndSet(100, 110, CAS3.asr.getStamp(), CAS3.asr.getStamp() + 1));
            System.out.println("2222222222222222======" + CAS3.asr.getStamp());
            System.out.println(CAS3.asr.compareAndSet(110, 100, CAS3.asr.getStamp(), CAS3.asr.getStamp() + 1));
            return;
        });

        final Thread tf2 = new Thread(() -> {
            int stamp = CAS3.asr.getStamp();
            System.out.println(stamp);
            try {
                TimeUnit.SECONDS.sleep(4L);
            } catch (InterruptedException e4) {
                e4.printStackTrace();
            }
            System.out.println("3333333333======" + CAS3.asr.getStamp());
            System.out.println(CAS3.asr.compareAndSet(100, 120, stamp, stamp + 1));
            return;
        });
        tf1.start();
        tf2.start();
    }
}
