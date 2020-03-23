package com.test.demo6_semaphore;

import java.util.concurrent.ConcurrentLinkedDeque;

public class CollectionDemo01 {
    public static void main(String[] args) throws InterruptedException{
        ConcurrentLinkedDeque<String> list=new ConcurrentLinkedDeque();
        //添加数据
        Thread[] add=new Thread[100];
        for (int i = 0; i < 100; i++) {
            add[i]=new Thread(()->{
                for (int j = 0; j < 10000; j++) {
                    list.add(Thread.currentThread().getName()+":Element "+j);
                }
            });
            System.out.println(" add  i " + i );
            add[i].start();
            // 之前对于join()方法只是了解它能够使得t.join()中的t优先执行，当t执行完后才会执行其他线程。
            // 能够使得线程之间的并行执行变成串行执行。
            add[i].join();
        }
        System.out.println("after add size:"+list.size());

        //移除数据

        Thread[] poll=new Thread[100];
        for (int i = 0; i < 100; i++) {
            poll[i]=new Thread(()->{
                for (int j = 0; j < 5000; j++) {
                    list.pollLast();
                    list.pollFirst();
                }
            });
            poll[i].start();
            poll[i].join();
        }
        System.out.println("after poll size:"+list.size());
    }
}
