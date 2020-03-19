package com.test.demo3_singleton;

public enum EnumSingleton {
    INSTANCE;
    public static EnumSingleton getInstance(){
        return INSTANCE;
    }
}

//holder
//枚举
//DCL
