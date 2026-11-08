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
import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.visitors.SaveCode;
import jadx.core.utils.tasks.TaskExecutor;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Runner {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("Expected request, output and progress paths");
        System.out.println(run(arguments[0], arguments[1], arguments[2]));
    }

    private static void write(File file, JSONObject value) throws Exception {
        File temp = new File(file.getPath() + ".part");
        Files.write(temp.toPath(), value.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static String run(String requestPath, String outputPath, String progressPath) throws Exception {
        long started = System.currentTimeMillis();
        JSONObject request = new JSONObject(new String(Files.readAllBytes(new File(requestPath).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        File output = new File(outputPath);
        output.mkdirs();
        java.io.PrintStream previousOut = System.out;
        java.io.PrintStream previousErr = System.err;
        java.io.PrintStream console = new java.io.PrintStream(new java.io.FileOutputStream(new File(output, "console.log")), true, "UTF-8");
        System.setOut(console);
        System.setErr(console);
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
            if (options.stream().noneMatch(value -> value.equals("--log-level") || value.equals("--quiet") || value.equals("-q") || value.equals("--verbose") || value.equals("-v"))) logger.setLevel(Level.INFO);
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
            report.put("workerPid", ProcessHandle.current().pid());
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("runtimeBackend", "jni-hotspot");
            report.put("staging", "shared-analysis-context/ordered-dex-output");
            report.put("metadataLoadCount", 1);
            report.put("crossDexContextPreserved", true);
            File progress = new File(progressPath);
            write(progress, new JSONObject().put("phase", "Loading input").put("completed", 0).put("total", 0));
            try (JadxDecompiler jadx = new JadxDecompiler(args)) {
                jadx.load();
                report.put("classes", jadx.getClasses().size());
                report.put("resources", jadx.getResources().size());
                JSONArray stages = new JSONArray();
                if (!args.isSkipResources()) {
                    write(progress, new JSONObject().put("phase", "Saving resources").put("completed", 0).put("total", jadx.getResources().size()));
                    jadx.saveResources();
                }
                AtomicInteger exported = new AtomicInteger();
                java.util.Set<String> saved = ConcurrentHashMap.newKeySet();
                if (!args.isSkipSources()) {
                    Map<String, List<JavaClass>> groups = new LinkedHashMap<>();
                    for (JavaClass cls : jadx.getClasses()) {
                        ClassNode node = cls.getClassNode();
                        if (node.contains(AFlag.DONT_GENERATE)) continue;
                        if (args.getClassFilter() != null && !args.getClassFilter().test(node.getClassInfo().getFullName())) continue;
                        String origin = node.getInputFileName();
                        if (origin == null) origin = "generated";
                        java.util.regex.Matcher dex = java.util.regex.Pattern.compile("classes(?:[0-9]+)?\\.dex").matcher(origin);
                        String key = dex.find() ? dex.group() : new File(origin).getName();
                        groups.computeIfAbsent(key, value -> new ArrayList<>()).add(cls);
                    }
                    int stageNumber = 0;
                    for (Map.Entry<String, List<JavaClass>> group : groups.entrySet()) {
                        stageNumber++;
                        long stageStarted = System.currentTimeMillis();
                        String phase = "DEX " + stageNumber + "/" + groups.size() + ": " + group.getKey();
                        int before = exported.get();
                        TaskExecutor executor = new TaskExecutor();
                        executor.setThreadsCount(args.getThreadsCount());
                        List<Runnable> tasks = new ArrayList<>();
                        for (List<JavaClass> batch : jadx.getDecompileScheduler().buildBatches(group.getValue())) {
                            tasks.add(() -> {
                                for (JavaClass cls : batch) {
                                    ClassNode node = cls.getClassNode();
                                    if (!saved.add(node.getRawName())) continue;
                                    try {
                                        SaveCode.save(args.getOutDirSrc(), node, node.getCode());
                                        exported.incrementAndGet();
                                    } catch (Exception error) {
                                        logger.error("Unable to save " + node.getRawName(), error);
                                        saved.remove(node.getRawName());
                                    }
                                }
                            });
                        }
                        executor.addParallelTasks(tasks);
                        executor.execute();
                        while (executor.isRunning()) {
                            write(progress, new JSONObject().put("phase", phase).put("stage", stageNumber).put("stages", groups.size()).put("completed", executor.getProgress()).put("total", executor.getTasksCount()));
                            Thread.sleep(1000);
                        }
                        executor.awaitTermination();
                        stages.put(new JSONObject().put("input", group.getKey()).put("classesSelected", group.getValue().size()).put("classesExported", exported.get() - before).put("elapsedMillis", System.currentTimeMillis() - stageStarted));
                    }
                }
                report.put("stages", stages);
                report.put("exportedClasses", exported.get());
                jadx.printErrorsReport();
                report.put("errors", jadx.getErrorsCount());
                report.put("warnings", jadx.getWarnsCount());
            }
            report.put("elapsedMillis", System.currentTimeMillis() - started);
            report.put("status", report.getInt("errors") == 0 ? "completed" : "completed-with-errors");
            write(new File(output, "engine-report.json"), report);
            return report.toString();
        } catch (Throwable error) {
            error.printStackTrace(console);
            throw error;
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
            context.stop();
            console.close();
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }
}
