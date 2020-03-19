package com.test.demo3_singleton;

public class EnumSingletonDemo {
    private EnumSingletonDemo(){
    }
    //延迟加载
    private enum EnumHolder{
        INSTANCE;
        private  EnumSingletonDemo instance=null;
        EnumHolder(){
            instance=new EnumSingletonDemo();
        }
        private EnumSingletonDemo getInstance(){
            return instance;
        }
    }//懒加载
    public static EnumSingletonDemo  getInstance(){
        return EnumHolder.INSTANCE.getInstance();
    }



    public static void main(String[] args) {
        for (int i = 0; i < 200; i++) {
            new Thread(() -> {
                System.out.println(getInstance());
            }).start();
        }

    }
}
