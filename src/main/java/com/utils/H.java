package com.utils;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class H {


    public static void sleep(int min, int max) {
        try {
            Random random = new Random();
            int time = random.nextInt(max) % (max - min + 1) + min;

            int divTime = time / 2000;
            int modTime = time % 2000;
            if (divTime > 0) {
                for (int i = 0; i < divTime; i++) {
                    TimeUnit.MILLISECONDS.sleep(2000);

                }
                TimeUnit.MILLISECONDS.sleep(modTime);
            } else {
                TimeUnit.MILLISECONDS.sleep(time);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
