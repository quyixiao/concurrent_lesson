package com.test.concurrent;

public class ShutDowsnDemo extends Thread {
    private volatile boolean started = false;

    @Override
    public void run(){
        while (started){
           // dowork();
        }
    }

    public void shutdown(){
        started = false;
    }




    public static void main(String[] args) {

    }
}
