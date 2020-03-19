package com.test.concurrent;

public class VolatileTest2 {
    static class A {
        volatile int a = 0;

        void increase() {
            a++;
        }

        int getA() {
            return a;
        }
    }

    public static void main(String[] args) {
        A a = new A();

        new Thread(() -> {
            for (int i = 0; i < 10000; i++) {
                a.increase();
            }
            System.out.println("111111111-------"+a.getA());
        }).start();
        new Thread(() -> {
            for (int i = 0; i < 20000; i++) {
                a.increase();
            }
            System.out.println("222222222---------"+a.getA());
        }).start();
        new Thread(() -> {
            for (int i = 0; i < 30000; i++) {
                a.increase();
            }
            System.out.println("333333333-------"+a.getA());
        }).start();
        new Thread(() -> {
            for (int i = 0; i < 40000; i++) {
                a.increase();
            }
            System.out.println("44444444444------"+a.getA());
        }).start();
        new Thread(() -> {
            for (int i = 0; i < 50000; i++) {
                a.increase();
            }
            System.out.println("55555555-------"+a.getA());
        }).start();
    }
}