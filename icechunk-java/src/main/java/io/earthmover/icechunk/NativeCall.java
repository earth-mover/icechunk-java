package io.earthmover.icechunk;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Receives the result of one native operation and hands it to the thread waiting for it.
 *
 * <p>The {@code on*} methods are called exactly once, from a native runtime thread or, for errors detected before
 * the operation starts, from the calling thread. They only store the value; the exception for a failure is built in
 * {@link #await} so that its stack trace shows the caller.
 */
final class NativeCall {
    /** Starts the operation and returns its task id. */
    @FunctionalInterface
    interface Starter {
        long start(NativeCall call);
    }

    private final CountDownLatch done = new CountDownLatch(1);
    private Object value;
    private String errorKind;
    private String errorMessage;

    static Object run(Starter starter) {
        NativeCall call = new NativeCall();
        long task = starter.start(call);
        return call.await(task);
    }

    static void runVoid(Starter starter) {
        run(starter);
    }

    static boolean runBoolean(Starter starter) {
        return (Boolean) run(starter);
    }

    static long runLong(Starter starter) {
        return (Long) run(starter);
    }

    /** Returns null when the native side reports "no value". */
    static String runString(Starter starter) {
        return (String) run(starter);
    }

    static List<String> runStrings(Starter starter) {
        return Collections.unmodifiableList(Arrays.asList((String[]) run(starter)));
    }

    /** Returns null when the key does not exist. */
    static byte[] runBytes(Starter starter) {
        return (byte[]) run(starter);
    }

    static byte[][] runBytesList(Starter starter) {
        return (byte[][]) run(starter);
    }

    private Object await(long task) {
        try {
            done.await();
        } catch (InterruptedException e) {
            Native.cancel(task);
            Thread.currentThread().interrupt();
            throw new IcechunkException("interrupted while waiting for icechunk", e);
        }
        if (errorKind != null) {
            throw IcechunkException.fromNative(errorKind, errorMessage);
        }
        return value;
    }

    private void finish(Object result) {
        value = result;
        done.countDown();
    }

    void onVoid() {
        finish(null);
    }

    void onBoolean(boolean result) {
        finish(result);
    }

    void onLong(long result) {
        finish(result);
    }

    void onBytes(byte[] result) {
        finish(result);
    }

    void onString(String result) {
        finish(result);
    }

    void onStrings(String[] result) {
        finish(result);
    }

    void onBytesList(byte[][] result) {
        finish(result);
    }

    void onError(String kind, String message) {
        errorKind = kind;
        errorMessage = message;
        done.countDown();
    }
}
