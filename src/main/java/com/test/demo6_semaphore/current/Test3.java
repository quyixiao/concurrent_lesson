package com.test.demo6_semaphore.current;

import com.sun.jmx.snmp.internal.SnmpOutgoingRequest;

import javax.crypto.interfaces.PBEKey;

public class Test3 {

    /**
     * The default initial table capacity.  Must be a power of 2
     * (i.e., at least 1) and at most MAXIMUM_CAPACITY.
     */
    private static final int DEFAULT_CAPACITY = 16;


    /**
     * Table initialization and resizing control.  When negative, the
     * table is being initialized or resized: -1 for initialization,
     * else -(1 + the number of active resizing threads).  Otherwise,
     * when table is null, holds the initial table size to use upon
     * creation, or 0 for default. After initialization, holds the
     * next element count value upon which to resize the table.
     */

    public static   transient volatile int sizeCtl = 0;

    public static void main(String[] args) {
        int sc = sizeCtl;

        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;

        System.out.println(n);
        System.out.println(n >>> 2);


        sc = n - (n >>> 2);
        System.out.println(sc);
    }
}
