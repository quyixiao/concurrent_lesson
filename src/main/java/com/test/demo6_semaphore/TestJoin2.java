package com.test.demo6_semaphore;


/***
 * 可以看出，join()方法的底层是利用wait()方法实现的。可以看出，join方法是一个同步方法，当主线程调用t1.join()方法时，
 * 主线程先获得了t1对象的锁，随后进入方法，调用了t1对象的wait()方法，使主线程进入了t1对象的等待池，此时，
 * A线程则还在执行，并且随后的t2.start()还没被执行，因此，B线程也还没开始。等到A线程执行完毕之后，主线程继续执行，
 * 走到了t2.start()，B线程才会开始执行。
 * 此外，对于join()的位置和作用的关系，我们可以用下面的例子来分析
 */

public class TestJoin2 {

    public static void main(String[] args) throws InterruptedException {
        // TODO Auto-generated method stub
        ThreadTest t1 = new ThreadTest("A");
        ThreadTest t2 = new ThreadTest("B");
        t1.start();
        t1.join();
        t2.start();

    }

}
