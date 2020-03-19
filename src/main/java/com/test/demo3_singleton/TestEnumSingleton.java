package com.test.demo3_singleton;

public class TestEnumSingleton {
    private TestEnumSingleton() {

    }

    enum EnumSingleton1 {
        INSTANCE;
        private TestEnumSingleton testEnumSingleton;

        private EnumSingleton1() {
            testEnumSingleton = new TestEnumSingleton();
        }

        public TestEnumSingleton getInstance() {
            return INSTANCE.testEnumSingleton;
        }
    }


    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                System.out.println(EnumSingleton1.INSTANCE.getInstance());
            }).start();
        }
    }

}
