package com.test.demo6_semaphore;

public class TestJoin4 {

    /***
     * 多次实验可以看出，主线程在t1.join()方法处停止，并需要等待A线程执行完毕后才会执行t3.start()，然而，并不影响B线程的执行。因此，
     * 可以得出结论，t.join()方法只会使主线程进入等待池并等待t线程执行完毕后才会被唤醒。并不影响同一时刻处在运行状态的其他线程。
     *
     * PS:join源码中，只会调用wait方法，并没有在结束时调用notify，这是因为线程在die的时候会自动调用自身的notifyAll方法，
     * 来释放所有的资源和锁。
     *
     *  
     * @param args
     * @throws InterruptedException
     */
    public static void main(String[] args) throws InterruptedException {
        // TODO Auto-generated method stub
        System.out.println(Thread.currentThread().getName() + " start");
        ThreadTest t1 = new ThreadTest("A");
        ThreadTest t2 = new ThreadTest("B");
        ThreadTest t3 = new ThreadTest("C");
        System.out.println("t1start");
        t1.start();
        System.out.println("t1end");
        System.out.println("t2start");
        t2.start();
        System.out.println("t2end");
        t1.join();
        System.out.println("t3start");
        t3.start();
        System.out.println("t3end");
        System.out.println(Thread.currentThread().getName() + " end");
    }


}
