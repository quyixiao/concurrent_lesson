package com.test.demo4_cas1;

import java.util.concurrent.atomic.AtomicInteger;

public class Test3 {


    public static void main(String[] args) {
        AtomicInteger a = new  AtomicInteger(310);
        // 如果值和给定的值是相等的，那么就直接更新为该值
        a.compareAndSet(30,40);
        System.out.println(a);

    }
}
