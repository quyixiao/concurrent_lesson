package com.test.demo6_semaphore;

public class TestJoin5 {

    public static void main(String[] args) {
        Thread t1 = new Thread() {
            @Override
            public void run() {
                System.out.println("t1 开始运行!");
                Thread t2 = new Thread() {
                    @Override
                    public void run() {
                        System.out.println("t2 开始运行!");
                        try {
                            Thread.sleep(3000);
                        } catch (Exception e) {
                        }
                        System.out.println("t2 结束运行!");
                    }
                };
                t2.start();
                try {
                    t2.join();
                } catch (Exception e) {
                }
                System.out.println("t1 结束运行!");
            }
        };
        t1.start();
    }
}
