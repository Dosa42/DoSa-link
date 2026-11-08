package com.ayrvokan.wireless;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeDebugger implements AutoCloseable {
    private final File directory;
    private final int pid;
    private final Context context;
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final FileWriter transcript;
    private final JSONArray symbols;
    private final Set<Long> breakpoints = new LinkedHashSet<>();
    private final Map<String, Integer> registers = new LinkedHashMap<>();
    private String stop = "";
    private volatile boolean running;
    private boolean detached;
    private boolean acknowledgments = true;

    public NativeDebugger(Context context, int target, File folder) throws Exception {
        pid = target; directory = folder; this.context = context.getApplicationContext();
        transcript = new FileWriter(new File(folder, "rsp.jsonl"), true);
        try (InputStream source = context.getAssets().open("workshop/debugger/symbols.json"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { JadxJobs.copy(source, bytes); symbols = new JSONObject(bytes.toString("UTF-8")).getJSONArray("symbols"); }
        Socket connected = null;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(250);
            File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libworkshop_lldb.so");
            context.startForegroundService(new android.content.Intent(context, NativeGuardianService.class).putExtra("id", folder.getName()).putExtra("port", listener.getLocalPort()).putExtra("targetPid", pid));
            long started = android.os.SystemClock.elapsedRealtime();
            while (connected == null) {
                try { connected = listener.accept(); }
                catch (java.net.SocketTimeoutException error) { if (new File(folder, "guardian-failure.json").isFile() || new File(folder, "guardian-worker-report.json").isFile()) throw new java.io.IOException("LLDB guardian startup failed: " + TerminalJobs.tail(new File(folder, "server-stderr.log"))); if (android.os.SystemClock.elapsedRealtime() - started > 15000) throw new java.io.IOException("LLDB reverse connection failed: " + TerminalJobs.tail(new File(folder, "server-stderr.log"))); }
            }
            socket = connected; socket.setTcpNoDelay(true); input = socket.getInputStream(); output = socket.getOutputStream();
            String supported = exchange("qSupported:xmlRegisters=aarch64");
            if (exchange("QStartNoAckMode").equals("OK")) acknowledgments = false;
            exchange("QSetDetachOnError:1");
            exchange("QPassSignals:0b;07");
            stop = exchange("?");
            for (int i = 0; i < 40; i++) {
                String info = exchange("qRegisterInfo" + Integer.toHexString(i));
                if (info.isEmpty() || info.startsWith("E")) break;
                String name = fields(info).get("name");
                if (name != null) registers.put(name, i);
                if ("cpsr".equals(name)) break;
            }
            JadxJobs.write(new File(directory, "server.json"), new JSONObject().put("executable", executable.getPath()).put("executableSha256", JadxJobs.hash(executable)).put("supported", supported).put("targetPid", pid).put("transport", "reverse-connect loopback through independent app guardian process").put("registers", new JSONObject(registers)));
        } catch (Exception error) { if (connected != null) connected.close(); context.stopService(new android.content.Intent(context, NativeGuardianService.class)); transcript.close(); throw error; }
    }

    private static Map<String, String> fields(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : value.split(";")) { int colon = part.indexOf(':'); if (colon > 0) result.put(part.substring(0, colon), part.substring(colon + 1)); }
        return result;
    }
    private void log(String direction, String packet) throws Exception { transcript.write(new JSONObject().put("utc", java.time.Instant.now().toString()).put("direction", direction).put("packet", packet).toString() + "\n"); transcript.flush(); }
    private void send(String packet) throws Exception {
        byte[] bytes = packet.getBytes(StandardCharsets.US_ASCII); int checksum = 0;
        for (byte value : bytes) checksum = (checksum + (value & 255)) & 255;
        synchronized (output) { output.write('$'); output.write(bytes); output.write(('#' + String.format(java.util.Locale.ROOT, "%02x", checksum)).getBytes(StandardCharsets.US_ASCII)); output.flush(); }
        log("request", packet);
    }
    private int next() throws Exception { int value = input.read(); if (value < 0) throw new java.io.EOFException("LLDB connection closed"); return value; }
    private String receive() throws Exception {
        for (;;) {
            int start = next(); if (start == '+' || start == '-') continue; if (start != '$' && start != '%') continue;
            ByteArrayOutputStream raw = new ByteArrayOutputStream(); int checksum = 0, value;
            while ((value = next()) != '#') { raw.write(value); checksum = (checksum + value) & 255; }
            int expected = Character.digit((char) next(), 16) * 16 + Character.digit((char) next(), 16);
            if (acknowledgments) synchronized (output) { output.write(checksum == expected ? '+' : '-'); output.flush(); }
            if (checksum != expected) throw new java.io.IOException("LLDB packet checksum mismatch");
            byte[] encoded = raw.toByteArray(); ByteArrayOutputStream decoded = new ByteArrayOutputStream(); int previous = 0;
            for (int i = 0; i < encoded.length; i++) {
                int c = encoded[i] & 255;
                if (c == '}') { if (++i == encoded.length) throw new java.io.IOException("Incomplete LLDB escape"); c = (encoded[i] & 255) ^ 32; }
                else if (c == '*') { if (++i == encoded.length) throw new java.io.IOException("Incomplete LLDB repeat"); for (int n = (encoded[i] & 255) - 29; n > 0; n--) decoded.write(previous); continue; }
                decoded.write(c); previous = c;
            }
            String packet = new String(decoded.toByteArray(), StandardCharsets.US_ASCII); log("response", packet);
            if (packet.matches("O[0-9a-fA-F]+")) continue;
            return packet;
        }
    }
    private String exchange(String packet) throws Exception {
        send(packet);
        for (;;) { String result = receive(); if (result.matches("[TS][0-9a-fA-F]{2}.*") && !packet.equals("?") && !packet.equals("c") && !packet.equals("s") && !packet.startsWith("C")) { stop = result; continue; } return result; }
    }
    private String checked(String packet) throws Exception { String result = exchange(packet); if (result.startsWith("E")) throw new java.io.IOException("LLDB " + packet + ": " + result); return result; }
    public static long address(String value) { return Long.parseUnsignedLong(value.replaceFirst("^0[xX]", ""), 16); }
    public static String hex(long value) { return "0x" + Long.toUnsignedString(value, 16); }
    private static long little(String value) { long result = 0; for (int i = 0; i < Math.min(value.length(), 16); i += 2) result |= (long) Integer.parseInt(value.substring(i, i + 2), 16) << (4 * i); return result; }
    public String memory(long pointer, int bytes) throws Exception { if (bytes < 1) throw new IllegalArgumentException("Memory size must be positive"); return checked("m" + Long.toHexString(pointer) + "," + Integer.toHexString(bytes)); }
    public String writeMemory(long pointer, String bytes) throws Exception { if (!bytes.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException("Enter hexadecimal bytes"); String result = checked("M" + Long.toHexString(pointer) + "," + Integer.toHexString(bytes.length() / 2) + ":" + bytes); if (!result.equals("OK")) throw new java.io.IOException(result); return memory(pointer, bytes.length() / 2); }
    public void breakpoint(long value, boolean insert) throws Exception { String result = checked((insert ? "Z0," : "z0,") + Long.toHexString(value) + ",4"); if (!result.equals("OK")) throw new java.io.IOException("Breakpoint response: " + result); if (insert) breakpoints.add(value); else breakpoints.remove(value); }
    public void interrupt() throws Exception { if (running) synchronized (output) { output.write(3); output.flush(); } }
    private long register(String... names) throws Exception { for (String name : names) if (registers.containsKey(name)) return little(checked("p" + Integer.toHexString(registers.get(name)))); throw new java.io.IOException("Register unavailable: " + java.util.Arrays.toString(names)); }
    public String resume(boolean step) throws Exception {
        long pc = register("pc");
        if (breakpoints.contains(pc)) {
            breakpoint(pc, false); running = true;
            try { stop = checked("s"); } finally { running = false; }
            breakpoint(pc, true);
            if (step) return stop;
        }
        running = true;
        try { stop = checked(step ? "s" : "c"); return stop; }
        finally { running = false; }
    }
    public JSONObject snapshot() throws Exception {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Integer> entry : registers.entrySet()) { String bytes = checked("p" + Integer.toHexString(entry.getValue())); values.put(entry.getKey(), new JSONObject().put("bytesLittleEndian", bytes).put("value", hex(little(bytes)))); }
        String thread = fields(stop.length() > 3 ? stop.substring(3) : "").get("thread");
        String threads = checked("qfThreadInfo"); String maps = JadxJobs.read(new File("/proc/" + pid + "/maps"));
        JSONArray frames = new JSONArray(); long pc = register("pc"), fp = register("fp", "x29"), lr = register("lr", "x30") & 0x0000ffffffffffffL;
        frames.put(resolve(pc, maps)); if (lr != pc) frames.put(resolve(lr, maps));
        Set<Long> seen = new LinkedHashSet<>();
        for (int i = 0; i < 32 && fp != 0 && seen.add(fp); i++) {
            try { String bytes = memory(fp, 16); long next = little(bytes.substring(0, 16)), returnAddress = little(bytes.substring(16, 32)) & 0x0000ffffffffffffL; if (returnAddress != lr && returnAddress != 0) frames.put(resolve(returnAddress, maps)); if (next <= fp || (next & 7) != 0) break; fp = next; }
            catch (Exception error) { frames.put(new JSONObject().put("unwindStopped", error.toString()).put("framePointer", hex(fp))); break; }
        }
        JSONArray points = new JSONArray(); for (long point : breakpoints) points.put(hex(point));
        return new JSONObject().put("backend", "lldb-server/GDB remote").put("phase", "paused").put("pid", pid).put("stopReply", stop).put("thread", thread).put("threads", threads).put("registers", values).put("frames", frames).put("stackKind", "ARM64 frame-pointer chain; no DWARF unwinder").put("breakpoints", points).put("capturedUtc", java.time.Instant.now().toString());
    }
    private JSONObject resolve(long pointer, String maps) throws Exception {
        JSONObject result = new JSONObject().put("address", hex(pointer)).put("addressKind", "runtime virtual address");
        for (String line : maps.split("\n")) {
            String[] part = line.trim().split("\\s+", 6); String[] range = part[0].split("-"); long begin = address(range[0]), end = address(range[1]);
            if (Long.compareUnsigned(pointer, begin) < 0 || Long.compareUnsigned(pointer, end) >= 0) continue;
            String path = part.length > 5 ? part[5] : ""; long base = 0; boolean found = false;
            for (String candidate : maps.split("\n")) {
                String[] mapped = candidate.trim().split("\\s+", 6);
                if (mapped.length == 6 && mapped[5].equals(path) && address(mapped[2]) == 0) { base = address(mapped[0].split("-")[0]); found = true; break; }
            }
            result.put("module", path);
            if (!found) { result.put("loadBiasAvailable", false); break; }
            long relative = pointer - base;
            result.put("loadBias", hex(base)).put("elfAddress", hex(relative));
            if (path.endsWith("libworkshop_debug.so")) for (int i = 0; i < symbols.length(); i++) { JSONObject symbol = symbols.getJSONObject(i); long start = address(symbol.getString("address")); if (relative >= start && relative < start + symbol.getLong("bytes")) { result.put("symbol", symbol.getString("name")).put("symbolOffset", hex(relative - start)).put("source", "Bundled agent.cpp; symbol addresses from the packaged ELF"); break; } }
            break;
        }
        return result;
    }
    public void detach() throws Exception { if (detached) return; for (long address : new ArrayList<>(breakpoints)) breakpoint(address, false); String result = exchange("D"); if (!result.equals("OK")) throw new java.io.IOException("Detach: " + result); detached = true; }
    @Override public void close() throws Exception {
        Exception failure = null;
        try { if (!detached && !running) detach(); } catch (Exception error) { failure = error; }
        try { socket.close(); }
        finally {
            File report = new File(directory, "guardian-worker-report.json"), error = new File(directory, "guardian-failure.json");
            while (!report.isFile() && !error.isFile()) Thread.sleep(25);
            File identity = new File(directory, "guardian-worker.json");
            if (identity.isFile()) { int worker = new JSONObject(JadxJobs.read(identity)).getInt("pid"); while (new File("/proc/" + worker).exists()) Thread.sleep(25); }
            transcript.close();
            if (error.isFile() && failure == null) failure = new java.io.IOException(JadxJobs.read(error));
        }
        if (failure != null) throw failure;
    }
}
