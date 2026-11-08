package com.ayrvokan.wireless;

import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RetainedBleBridge {
    public interface Sink { void result(String method, boolean success, Object value); }
    private final Object module;
    private final Class<?> promise;
    private final Sink sink;
    private boolean closed;

    public RetainedBleBridge(Context context, Sink sink) throws Exception {
        this.sink = sink;
        ClassLoader loader = context.getClassLoader();
        Class.forName("com.facebook.soloader.SoLoader").getMethod("init", Context.class, boolean.class).invoke(null, context.getApplicationContext(), false);
        Class.forName("com.facebook.react.bridge.ReactBridge").getMethod("staticInit").invoke(null);
        Class<?> react = Class.forName("com.facebook.react.bridge.ReactApplicationContext");
        Object reactContext = react.getConstructor(Context.class).newInstance(context.getApplicationContext());
        Class<?> catalyst = Class.forName("com.facebook.react.bridge.CatalystInstance");
        Object eventAdapter = Proxy.newProxyInstance(loader, new Class<?>[] {catalyst}, (proxy, method, args) -> {
            if (method.getName().equals("getJSModule")) {
                Class<?> contract = (Class<?>) args[0];
                return Proxy.newProxyInstance(loader, new Class<?>[] {contract}, (eventProxy, eventMethod, values) -> {
                    if (eventMethod.getDeclaringClass() == Object.class) return objectMethod(eventProxy, eventMethod, values);
                    if (!closed) sink.result("event:" + values[0], true, values.length > 1 ? values[1] : null);
                    return null;
                });
            }
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
        java.lang.reflect.Field field = Class.forName("com.facebook.react.bridge.ReactContext").getDeclaredField("mCatalystInstance");
        field.setAccessible(true); field.set(reactContext, eventAdapter);
        promise = Class.forName("com.facebook.react.bridge.Promise");
        module = Class.forName("com.pair.bluetooth.coolkit.BLEPairModule").getConstructor(react).newInstance(reactContext);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("toString")) return "WorkshopLocalModuleAdapter";
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        return proxy == args[0];
    }

    public void call(String name, Object... args) throws Exception {
        if (closed) throw new IllegalStateException("Module connection closed");
        Method selected = null;
        for (Method method : module.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterTypes().length == args.length + 1 && method.getParameterTypes()[args.length] == promise) selected = method;
        if (selected == null) throw new NoSuchMethodException(name);
        Object callback = Proxy.newProxyInstance(promise.getClassLoader(), new Class<?>[] {promise}, (proxy, method, values) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, values);
            if (!closed) {
                if (method.getName().equals("resolve")) sink.result(name, true, values == null || values.length == 0 ? null : values[0]);
                else if (method.getName().equals("reject")) sink.result(name, false, values == null ? "Rejected" : java.util.Arrays.toString(values));
            }
            return null;
        });
        Object[] parameters = java.util.Arrays.copyOf(args, args.length + 1); parameters[args.length] = callback;
        selected.invoke(module, parameters);
    }

    public String connectionState() throws Exception {
        Object controller = Class.forName("fa.a").getMethod("A").invoke(null);
        return (String) controller.getClass().getMethod("y").invoke(controller);
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { module.getClass().getMethod("disconnect").invoke(module); }
        catch (Exception error) { android.util.Log.w("WorkshopPairing", "Retained disconnect failed", error); }
    }

    public static Object json(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof JSONObject || value instanceof JSONArray) return value;
        if (value instanceof java.util.Map) return new JSONObject((java.util.Map<?, ?>) value);
        if (value instanceof java.util.Collection) return new JSONArray((java.util.Collection<?>) value);
        try { return json(value.getClass().getMethod("toHashMap").invoke(value)); } catch (NoSuchMethodException error) { }
        try { return json(value.getClass().getMethod("toArrayList").invoke(value)); } catch (NoSuchMethodException error) { }
        return value.toString();
    }
}
