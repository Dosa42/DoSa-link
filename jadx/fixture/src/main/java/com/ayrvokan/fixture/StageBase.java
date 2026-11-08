package com.ayrvokan.fixture;

public class StageBase<T> {
    public T echo(T value) { return value; }

    public static final class Nested {
        public static int value() { return 7; }
    }
}
