package com.test.demo4_cas1;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicStampedReference;

public class CAS2 {
    private static volatile int m;
    private static AtomicInteger atomicI;
    private static AtomicStampedReference asr;

    public static void main(final String[] args) throws InterruptedException {
        final Thread t1 = new Thread(() -> System.out.println(CAS2.atomicI.compareAndSet(100, 110)));
        t1.start();
        final Thread t2 = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(2L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(CAS2.atomicI.compareAndSet(110, 100));
            return;
        });
        t2.start();


        final Thread t3 = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(3L);
            } catch (InterruptedException e2) {
                e2.printStackTrace();
            }
            System.out.println(CAS2.atomicI.compareAndSet(100, 120));
            return;
        });
        t3.start();
        System.out.println("==========================================");
    }

    static {
        CAS2.m = 0;
        CAS2.atomicI = new AtomicInteger(100);
        CAS2.asr = new AtomicStampedReference(100, 1);
    }
}
