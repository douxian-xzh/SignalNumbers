package de.robv.android.xposed;

import java.util.Set;

public final class XposedBridge {
    public static Set<?> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
    public static void log(Throwable throwable) {}
}
