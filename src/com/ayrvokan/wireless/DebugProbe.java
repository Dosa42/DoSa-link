package com.ayrvokan.wireless;

public final class DebugProbe {
    public static int calculate(int left, int right) {
        int product = left * right;
        int observed = mark(product);
        return observed + 1;
    }
    public static int mark(int observed) {
        int value = observed;
        return value;
    }
}
