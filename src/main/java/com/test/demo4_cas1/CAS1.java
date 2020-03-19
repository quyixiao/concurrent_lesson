package com.test.demo4_cas1;

import java.util.concurrent.atomic.AtomicInteger;

public class CAS1 {
    private static volatile int m;
    private static AtomicInteger atomicI;

    public static void increase1() {
        ++CAS1.m;
    }

    public static void increase2() {
        CAS1.atomicI.incrementAndGet();
    }

    public static void main(final String[] args) throws InterruptedException {
        final Thread[] t = new Thread[20];
        for (int i = 0; i < 20; ++i) {
            (t[i] = new Thread(() -> increase1())).start();
            t[i].join();
        }
        System.out.println(CAS1.m);
        final Thread[] tf = new Thread[20];
        for (int j = 0; j < 20; ++j) {
            (tf[j] = new Thread(() -> increase2())).start();
            tf[j].join();
        }
        System.out.println("atomic:" + CAS1.atomicI.get());
    }

    static {
        CAS1.m = 0;
        CAS1.atomicI = new AtomicInteger(0);
    }
}
