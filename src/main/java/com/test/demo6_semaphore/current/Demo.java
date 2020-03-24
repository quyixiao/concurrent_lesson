package com.test.demo6_semaphore.current;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/***
 * HashMap类中有一个comparableClassFor(Object x)方法，当x的类型为X，且X直接实现了Comparable接口（比较类型必须为X类本身）时，
 * 返回x的运行时类型；否则返回null。通过这个方法，我们可以搞清楚一些与类型、泛型相关的概念和方法。
 */
public class Demo {
    public static void main(String[] args) {
        System.out.println(comparableClassFor(new A()));    // null,A does not implement Comparable.
        System.out.println("=================AAAAAAAAAAaaaa=============================");
        System.out.println(comparableClassFor(new B()));    // null,B implements Comparable, compare to Object.
        System.out.println("===============BBBBBBBBBBBBBBBBBBBBBb================================");
        System.out.println(comparableClassFor(new C()));    // class Demo$C,C implements Comparable, compare to itself.
        System.out.println("======================CCCCCCCCCCC===================================");
        System.out.println(comparableClassFor(new D()));    // null,D implements Comparable, compare to its sub type.
        System.out.println("====================DDDDDDDDDDDDDDDDddd===============================");
        System.out.println(comparableClassFor(new F()));    // null,F is C's sub type.
    }

    static class A {
    }

    static class B implements Comparable<Object> {
        @Override
        public int compareTo(Object o) {
            return 0;
        }
    }

    static class C implements Comparable<C> {
        @Override
        public int compareTo(C o) {
            return 0;
        }

    }

    static class D implements Comparable<E> {
        @Override
        public int compareTo(E o) {
            return 0;
        }
    }

    static class E {
    }

    static class F extends C {
    }

    /**
     * Returns x's Class if it is of the form "class C implements
     * Comparable<C>", else null.
     */
    static Class<?> comparableClassFor(Object x) {
        //insanceof可以理解为是某种类型的实例，无论是运行时类型，还是它的父类，它实现的接口，他的父类实现的接口，甚至它的父类的父类的
        // 父类实现的接口的父类的父类，总之，只要在继承链上有这个类型就可以了
        if (x instanceof Comparable) {
            Class<?> c;
            Type[] ts, as;
            Type t;
            ParameterizedType p;
            System.out.println("xxxxxxxxxxxxxxx" + x.getClass());
            if ((c = x.getClass()) == String.class) {// bypass checks
                System.out.println("============" + c);
                return c;
            }
            // getGenericInterfaces()方法返回的是该对象的运行时类型“直接实现”的接口，这意味着：
            // 返回的一定是接口
            // 必然是该类型自己实现的接口，继承过来的不算
            if ((ts = c.getGenericInterfaces()) != null) {
                System.out.println("------------" + c.getGenericInterfaces() + "   " +  ts.length);
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                            ((p = (ParameterizedType) t).getRawType() ==
                                    Comparable.class) &&
                            (as = p.getActualTypeArguments()) != null &&
                            as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }
}