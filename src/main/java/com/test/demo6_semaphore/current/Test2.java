package com.test.demo6_semaphore.current;

import com.test.demo6_semaphore.MyConcurrentHashMap;

public class Test2 {

    public static void main(String[] args) {
        int a = 20>>>16;
        System.out.println(a);
        int b = MyConcurrentHashMap.spread(20);
        System.out.println(b);
        int h = 20;
        System.out.println((h ^ (h >>> 16)));
        System.out.println((h >>> 16));
        System.out.println(20^2);
    }
}
