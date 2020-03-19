package com.test.demo3_singleton;
// 线程不安全
// 线程安全性，
public class HoonSingleton {
    private static HoonSingleton instance=null;
    private HoonSingleton(){
    }
    public static HoonSingleton getInstance(){
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(null==instance)
            instance=new HoonSingleton();
        return instance;
    }

    public static void main(String[] args) {
        for (int i = 0; i < 200; i++) {
            new Thread(()->{
                System.out.println(HoonSingleton.getInstance());
            }).start();
        }
    }
}
