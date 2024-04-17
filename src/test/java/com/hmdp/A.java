package com.hmdp;

public class A {
    private static A a = new A();

    public static A getA() {
        return a;
    }
}
