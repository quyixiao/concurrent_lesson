package com.test.demo6_semaphore;

public class TestJoin3 {


    public static void main(String[] args) throws InterruptedException {
        // TODO Auto-generated method stub
        System.out.println(Thread.currentThread().getName()+" start");
        ThreadTest t1=new ThreadTest("A");
        ThreadTest t2=new ThreadTest("B");
        ThreadTest t3=new ThreadTest("C");
        System.out.println("t1start");
        t1.start();
        System.out.println("t2start");
        t2.start();
        System.out.println("t3start");
        t3.start();
        System.out.println(Thread.currentThread().getName()+" end");
    }




}
