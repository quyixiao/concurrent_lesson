package com.test.concurrent;

import com.utils.H;

public class ReaderAndUpdater {


    final static int MAX = 50;

    //volatile关键字的作用：保证了变量的可见性（visibility）。被volatile关键字修饰的变量，如果值发生了变更，
    // 其他线程立马可见，
    //注意：volatile只能保证变量的可见性，不能保证对volatile变量操作的原子性，见如下代码：
    static volatile int int_value = 0;


    public static void main(String[] args) {
        new Thread(new Runnable() {
            public void run() {
                int localValue = int_value;
                while (localValue < MAX) {
                    if (localValue < MAX) {
                        System.out.println(" Reader : " + int_value);
                        localValue = int_value;

                    }
                }
            }
        }, "Reader").start();

        new Thread(new Runnable() {
            public void run() {
                int localValue = int_value;
                while (localValue < MAX) {
                    System.out.println("Update  [  " + (++localValue) + "]");
                    int_value = localValue;
                    H.sleep(10, 20);
                }
            }
        }).start();
    }
}
