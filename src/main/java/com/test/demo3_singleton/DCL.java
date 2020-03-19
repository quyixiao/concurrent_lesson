package com.test.demo3_singleton;

import java.net.Socket;
import java.sql.Connection;

//Double-check-locking
public class DCL {
    Connection conn;
    Socket socket;
    private volatile static DCL instance = null;

    private DCL() {
    }

    public static DCL getInstance() {
        if (null == instance)
            synchronized (DCL.class) {
                if (null == instance)
                    instance = new DCL();
            }
        return instance;
    }

    public static void main(String[] args) {
        for (int i = 0; i < 2000; i++) {
            new Thread(() -> {
                System.out.println(DCL.getInstance());
            }).start();
        }
    }
}

