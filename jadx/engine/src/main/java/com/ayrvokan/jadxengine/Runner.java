package com.ayrvokan.jadxengine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.JadxPlugin;
import jadx.cli.JadxCLIArgs;
import jadx.core.plugins.files.IJadxFilesGetter;
import jadx.api.security.JadxSecurityFlag;
import jadx.api.security.impl.JadxSecurity;

public final class Runner {
    private static void write(File file, JSONObject value) throws Exception {
        File temp = new File(file.getPath() + ".part");
        Files.write(temp.toPath(), value.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static String run(String requestPath, String outputPath, String progressPath) throws Exception {
        long started = System.currentTimeMillis();
        JSONObject request = new JSONObject(Files.readString(new File(requestPath).toPath()));
        File output = new File(outputPath);
        output.mkdirs();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%date %-5level %logger - %msg%n");
        encoder.start();
        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setFile(new File(output, "jadx.log").getPath());
        appender.setEncoder(encoder);
        appender.start();
        ch.qos.logback.classic.Logger logger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            List<String> options = new ArrayList<>();
            JSONArray advanced = request.optJSONArray("options");
            if (advanced != null) for (int i = 0; i < advanced.length(); i++) options.add(advanced.getString(i));
            String selected = request.optString("selectedClass");
            if (!selected.isEmpty()) {
                options.add("--single-class");
                options.add(selected);
            }
            options.add(request.getString("input"));
            JadxCLIArgs cli = new JadxCLIArgs();
            if (!cli.processArgs(options.toArray(new String[0]))) throw new IllegalArgumentException("JADX did not accept these options");
            JadxArgs args = cli.toJadxArgs();
            args.setOutDir(output);
            if (!selected.isEmpty()) args.setClassFilter(name -> name.equals(selected) || name.startsWith(selected + "$"));
            args.setOutDirSrc(new File(output, "sources"));
            args.setOutDirRes(new File(output, "resources"));
            File runtime = new File(output, ".runtime");
            args.setFilesGetter(new IJadxFilesGetter() {
                private Path directory(String name) {
                    File dir = new File(runtime, name);
                    dir.mkdirs();
                    return dir.toPath();
                }
                @Override public Path getConfigDir() { return directory("config"); }
                @Override public Path getCacheDir() { return directory("cache"); }
                @Override public Path getTempDir() { return directory("temp"); }
            });
            java.util.Set<JadxSecurityFlag> flags = JadxSecurityFlag.all();
            flags.remove(JadxSecurityFlag.SECURE_XML_PARSER);
            args.setSecurity(new JadxSecurity(flags));
            JSONArray plugins = new JSONArray();
            for (JadxPlugin plugin : ServiceLoader.load(JadxPlugin.class, Runner.class.getClassLoader())) plugins.put(plugin.getPluginInfo().getPluginId());
            JSONObject report = new JSONObject();
            report.put("engine", "jadx-headless");
            report.put("version", JadxDecompiler.getVersion());
            report.put("plugins", plugins);
            report.put("threads", args.getThreadsCount());
            report.put("options", new JSONArray(options));
            report.put("classLoader", Runner.class.getClassLoader().toString());
            report.put("loggerClassLoader", LoggerFactory.class.getClassLoader().toString());
            report.put("runtime", System.getProperty("java.vm.name"));
            report.put("heapLimit", Runtime.getRuntime().maxMemory());
            report.put("workerPid", android.os.Process.myPid());
            File progress = new File(progressPath);
            write(progress, new JSONObject().put("phase", "Loading input").put("completed", 0).put("total", 0));
            try (JadxDecompiler jadx = new JadxDecompiler(args)) {
                jadx.load();
                report.put("classes", jadx.getClasses().size());
                report.put("resources", jadx.getResources().size());
                jadx.save(1000, (done, total) -> {
                    try { write(progress, new JSONObject().put("phase", "Decompiling").put("completed", done).put("total", total)); }
                    catch (Exception error) { logger.warn("Unable to record progress", error); }
                });
                jadx.printErrorsReport();
                report.put("errors", jadx.getErrorsCount());
                report.put("warnings", jadx.getWarnsCount());
            }
            report.put("elapsedMillis", System.currentTimeMillis() - started);
            report.put("status", report.getInt("errors") == 0 ? "completed" : "completed-with-errors");
            write(new File(output, "engine-report.json"), report);
            return report.toString();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
        }
    }
}
