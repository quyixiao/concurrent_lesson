package com.test.demo7_pool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FixedPoolDemo {

    public static void main(String[] args) {
        //创建固定大小线程池
        ExecutorService pool=Executors.newFixedThreadPool(5);
        //创建10个任务给pool
        for (int i = 0; i < 10; i++) {
            //创建任务
            Runnable task=new TaskDemo();
            //把任务交给pool去执行
            pool.execute(task);
        }
        //关闭
        pool.shutdown();//shutdown
        // 注意除非首先调用shutdown或shutdownNow，否则isTerminated永不为true
        // 若关闭后所有任务都已完成，则返回true。
        while (!pool.isTerminated()){

        }

        System.out.println("finished");
    }

}
