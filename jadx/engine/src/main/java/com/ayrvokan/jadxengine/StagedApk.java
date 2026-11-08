package com.ayrvokan.jadxengine;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.plugins.input.ICodeLoader;
import jadx.api.plugins.input.data.IClassData;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.visitors.SaveCode;
import jadx.core.utils.tasks.TaskExecutor;
import jadx.plugins.input.dex.DexInputPlugin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StagedApk {
    interface ArgsFactory {
        JadxArgs create(File output) throws Exception;
    }

    private static final Logger LOG = LoggerFactory.getLogger(StagedApk.class);
    private static final int SOURCE_BATCH_CLASS_TARGET = 512;

    private static final class Unit {
        final String origin;
        final File input;
        final boolean dex;
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> owned = new LinkedHashSet<>();
        String stageName;
        Unit(String origin, File input, boolean dex) { this.origin = origin; this.input = input; this.dex = dex; this.stageName = origin; }
    }

    private static final class Index {
        final Map<String, ClspClass> types = new HashMap<>();
        final Map<String, Unit> origins = new LinkedHashMap<>();
        final Map<String, String> parents = new HashMap<>();
        int errors;
        int warnings;
    }

    private static File inside(File root, String name) throws Exception {
        File result = new File(root, name);
        if (!result.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IllegalArgumentException("Invalid archive path: " + name);
        result.getParentFile().mkdirs();
        return result;
    }

    private static String raw(String type) {
        return (type.startsWith("L") && type.endsWith(";") ? type.substring(1, type.length() - 1) : type).replace('/', '.');
    }

    private static int dexNumber(String name) {
        String leaf = new File(name).getName();
        if (!leaf.matches("classes[0-9]*\\.dex")) return Integer.MAX_VALUE;
        String number = leaf.substring(7, leaf.length() - 4);
        return number.isEmpty() ? 1 : Integer.parseInt(number);
    }

    private static List<Unit> extract(File input, File scratch, File resources, boolean saveResources) throws Exception {
        List<Unit> units = new ArrayList<>();
        try (ZipFile zip = new ZipFile(input); ZipOutputStream metadata = new ZipOutputStream(Files.newOutputStream(new File(scratch, "resources.apk").toPath()))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int jar = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".dex")) {
                    File target = inside(scratch, "dex/" + name);
                    try (InputStream from = zip.getInputStream(entry); OutputStream to = Files.newOutputStream(target.toPath())) { from.transferTo(to); }
                    units.add(new Unit(name, target, true));
                    continue;
                }
                if (name.endsWith(".jar")) {
                    File target = inside(scratch, "jars/" + jar++ + ".jar");
                    try (InputStream from = zip.getInputStream(entry); OutputStream to = Files.newOutputStream(target.toPath())) { from.transferTo(to); }
                    units.add(new Unit(name, target, false));
                }
                if (!name.endsWith(".jar") && (name.equals("AndroidManifest.xml") || name.equals("resources.arsc") || name.startsWith("res/"))) {
                    metadata.putNextEntry(new ZipEntry(name));
                    try (InputStream from = zip.getInputStream(entry)) { from.transferTo(metadata); }
                    metadata.closeEntry();
                } else if (saveResources) {
                    File target = inside(resources, name);
                    try (InputStream from = zip.getInputStream(entry); OutputStream to = Files.newOutputStream(target.toPath())) { from.transferTo(to); }
                }
            }
        }
        units.sort((left, right) -> left.dex && right.dex ? Integer.compare(dexNumber(left.origin), dexNumber(right.origin)) : left.dex ? -1 : right.dex ? 1 : left.origin.compareTo(right.origin));
        return units;
    }

    private static void headers(Unit unit, Index index, ArgsFactory factory, File scratch) throws Exception {
        JadxArgs args = factory.create(new File(scratch, "headers"));
        args.setInputFiles(List.of(unit.input));
        args.setClassFilter(null);
        args.setSkipResources(true);
        args.setSkipSources(true);
        try (JadxDecompiler jadx = new JadxDecompiler(args)) {
            jadx.load();
            if (unit.dex) try (ICodeLoader loader = dexLoader(jadx, List.of(unit.input.toPath()))) {
                loader.visitClasses(data -> unit.names.add(raw(data.getType())));
            }
            for (ClassNode cls : jadx.getRoot().getClasses(true)) {
                String name = cls.getRawName();
                if (unit.dex && !unit.names.contains(name)) continue;
                if (!unit.dex && cls.getClsData() == null) continue;
                if (!unit.dex) unit.names.add(name);
                ClspClass type = jadx.getRoot().getClsp().getClsDetails(cls.getClassInfo().getType());
                if (type != null) index.types.putIfAbsent(name, type);
                ClassInfo parent = cls.getClassInfo().getParentClass();
                if (parent != null) index.parents.put(name, parent.getRawName());
            }
            index.errors += jadx.getErrorsCount();
            index.warnings += jadx.getWarnsCount();
        }
        for (String name : unit.names) index.origins.putIfAbsent(name, unit);
    }

    private static String family(String name, Index index) {
        String current = name;
        Set<String> seen = new HashSet<>();
        while (seen.add(current)) {
            String parent = index.parents.get(current);
            if (parent == null) {
                int split = current.indexOf('$');
                if (split > 0) parent = current.substring(0, split);
            }
            if (parent == null || !index.origins.containsKey(parent)) break;
            current = parent;
        }
        return current;
    }

    private static List<Unit> sourceUnits(List<Unit> inputs, Index index) {
        List<Unit> result = new ArrayList<>();
        for (Unit input : inputs) {
            if (!input.dex) { result.add(input); continue; }
            Map<String, List<String>> families = new java.util.TreeMap<>();
            for (String name : input.owned) families.computeIfAbsent(family(name, index), key -> new ArrayList<>()).add(name);
            List<Unit> batches = new ArrayList<>();
            Unit batch = null;
            for (List<String> members : families.values()) {
                if (batch == null || !batch.owned.isEmpty() && batch.owned.size() + members.size() > SOURCE_BATCH_CLASS_TARGET) {
                    batch = new Unit(input.origin, input.input, true);
                    batches.add(batch);
                }
                batch.owned.addAll(members);
                batch.names.addAll(members);
            }
            for (int part = 0; part < batches.size(); part++) {
                Unit unit = batches.get(part);
                unit.stageName = input.origin + " [" + (part + 1) + "/" + batches.size() + "]";
                result.add(unit);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static int importSignatures(JadxDecompiler jadx, Index primary, Index fallback) throws Exception {
        Field field = ClspGraph.class.getDeclaredField("nameMap");
        field.setAccessible(true);
        Map<String, ClspClass> target = (Map<String, ClspClass>) field.get(jadx.getRoot().getClsp());
        Set<String> local = new HashSet<>();
        for (ClassNode cls : jadx.getRoot().getClasses(true)) local.add(cls.getRawName());
        for (Map.Entry<String, ClspClass> entry : fallback.types.entrySet()) if (!local.contains(entry.getKey())) target.put(entry.getKey(), entry.getValue());
        for (Map.Entry<String, ClspClass> entry : primary.types.entrySet()) if (!local.contains(entry.getKey())) target.put(entry.getKey(), entry.getValue());
        jadx.getRoot().getClsp().initCache();
        return primary.types.size() + fallback.types.size();
    }

    private static ICodeLoader dexLoader(JadxDecompiler owner, List<Path> paths) {
        for (var context : owner.getPluginManager().getResolvedPluginContexts()) {
            if (context.getPluginInstance() instanceof DexInputPlugin plugin) return plugin.loadFiles(paths);
        }
        throw new IllegalStateException("The selected APK stage requires the active dex-input plugin");
    }

    private static ICodeLoader filtered(JadxDecompiler owner, List<Path> paths, Set<String> selected, AtomicInteger visited) {
        return new ICodeLoader() {
            private ICodeLoader delegate;
            @Override public void visitClasses(Consumer<IClassData> consumer) {
                if (delegate == null) delegate = dexLoader(owner, paths);
                delegate.visitClasses(data -> { if (selected.contains(raw(data.getType()))) { visited.incrementAndGet(); consumer.accept(data); } });
            }
            @Override public boolean isEmpty() { return selected.isEmpty(); }
            @Override public void close() throws java.io.IOException { if (delegate != null) delegate.close(); }
        };
    }

    private static JSONObject sources(Unit unit, List<Path> dexFiles, Index primary, Index fallback, File metadata, File output, File progress, ArgsFactory factory, Set<String> exported, int stage, int count) throws Exception {
        long started = System.currentTimeMillis();
        File destination = unit.dex ? output : inside(output, "embedded/" + unit.origin + "/output");
        JadxArgs args = factory.create(destination);
        args.setSkipResources(true);
        args.setInputFiles(unit.dex ? List.of(metadata) : List.of(unit.input));
        int loaded;
        int errors;
        int warnings;
        int imports;
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger visited = new AtomicInteger();
        try (JadxDecompiler jadx = new JadxDecompiler(args)) {
            if (unit.dex) jadx.addCustomCodeLoader(filtered(jadx, dexFiles, unit.owned, visited));
            jadx.load();
            loaded = jadx.getClasses().size();
            imports = importSignatures(jadx, primary, fallback);
            List<JavaClass> selected = new ArrayList<>();
            for (JavaClass cls : jadx.getClasses()) {
                ClassNode node = cls.getClassNode();
                if (node.contains(AFlag.DONT_GENERATE)) continue;
                java.util.function.Predicate<String> mask = args.getClassFilter();
                if (mask != null && !mask.test(node.getClassInfo().getFullName()) && unit.owned.stream().noneMatch(name -> name.startsWith(node.getRawName() + "$" ) && mask.test(name))) continue;
                String identity = (unit.dex ? "dex:" : unit.origin + ":") + node.getRawName();
                if (exported.add(identity)) selected.add(cls);
            }
            TaskExecutor tasks = new TaskExecutor();
            tasks.setThreadsCount(args.getThreadsCount());
            List<Runnable> work = new ArrayList<>();
            for (List<JavaClass> batch : jadx.getDecompileScheduler().buildBatches(selected)) {
                work.add(() -> {
                    for (JavaClass cls : batch) {
                        ClassNode node = cls.getClassNode();
                        try { SaveCode.save(args.getOutDirSrc(), node, node.getCode()); saved.incrementAndGet(); }
                        catch (Exception error) { failures.incrementAndGet(); LOG.error("Unable to save " + node.getRawName(), error); }
                    }
                });
            }
            tasks.addParallelTasks(work);
            tasks.execute();
            while (tasks.isRunning()) {
                Runner.write(progress, new JSONObject().put("phase", "Sources " + stage + "/" + count + ": " + unit.stageName).put("stage", stage).put("stages", count).put("completed", tasks.getProgress()).put("total", tasks.getTasksCount()));
                Thread.sleep(1000);
            }
            tasks.awaitTermination();
            jadx.printErrorsReport();
            errors = jadx.getErrorsCount() + failures.get();
            warnings = jadx.getWarnsCount();
        }
        return new JSONObject().put("input", unit.origin).put("sourceBatch", unit.stageName).put("kind", unit.dex ? "dex" : "embedded-jar").put("inputClassDefinitions", unit.names.size()).put("ownedClassDefinitions", unit.dex ? unit.owned.size() : unit.names.size()).put("classDefinitionsVisited", unit.dex ? visited.get() : unit.names.size()).put("topLevelClassesLoaded", loaded).put("signatureIndexEntries", imports).put("classesExported", saved.get()).put("errors", errors).put("warnings", warnings).put("elapsedMillis", System.currentTimeMillis() - started);
    }

    static JSONObject run(JadxArgs base, ArgsFactory factory, File progress) throws Exception {
        File output = base.getOutDir();
        File scratch = new File(output, ".runtime/staging");
        scratch.mkdirs();
        File metadata = new File(scratch, "resources.apk");
        Runner.write(progress, new JSONObject().put("phase", "Indexing APK inputs").put("completed", 0).put("total", 0));
        List<Unit> units = extract(base.getInputFiles().get(0), scratch, new File(output, "resources"), !base.isSkipResources());
        Index dex = new Index();
        Index jars = new Index();
        JSONArray inventory = new JSONArray();
        JSONArray headerStages = new JSONArray();
        int headerStage = 0;
        for (Unit unit : units) {
            headerStage++;
            Runner.write(progress, new JSONObject().put("phase", "Signatures " + headerStage + "/" + units.size() + ": " + unit.origin).put("stage", headerStage).put("stages", units.size()).put("completed", headerStage - 1).put("total", units.size()));
            headers(unit, unit.dex ? dex : jars, factory, scratch);
            inventory.put(new JSONObject().put("input", unit.origin).put("kind", unit.dex ? "dex" : "embedded-jar").put("classDefinitions", unit.names.size()));
            System.gc();
            headerStages.put(new JSONObject().put("input", unit.origin).put("classDefinitions", unit.names.size()).put("heapUsedAfterRelease", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        }
        int relocated = 0;
        for (Map.Entry<String, Unit> entry : dex.origins.entrySet()) {
            Unit owner = dex.origins.get(family(entry.getKey(), dex));
            if (owner == null) owner = entry.getValue();
            owner.owned.add(entry.getKey());
            if (owner != entry.getValue()) relocated++;
        }
        Runner.write(new File(output, "staged-inputs.json"), new JSONObject().put("inputs", inventory).put("dexSignatures", dex.types.size()).put("embeddedJarSignatures", jars.types.size()).put("crossDexFamilyRelocations", relocated));
        int errors = dex.errors + jars.errors;
        int warnings = dex.warnings + jars.warnings;
        if (!base.isSkipResources()) {
            Runner.write(progress, new JSONObject().put("phase", "Saving APK resources").put("completed", 0).put("total", 0));
            JadxArgs args = factory.create(output);
            args.setInputFiles(List.of(metadata));
            args.setSkipSources(true);
            try (JadxDecompiler jadx = new JadxDecompiler(args)) {
                jadx.load();
                jadx.saveResources();
                errors += jadx.getErrorsCount();
                warnings += jadx.getWarnsCount();
            }
            System.gc();
        }
        List<Unit> sourceUnits = sourceUnits(units, dex);
        JSONArray stages = new JSONArray();
        Set<String> exported = new HashSet<>();
        int stage = 0;
        int topLevel = 0;
        int generated = 0;
        int visitedDexDefinitions = 0;
        if (!base.isSkipSources()) for (Unit unit : sourceUnits) {
            stage++;
            if (unit.dex && unit.owned.isEmpty()) continue;
            Set<Path> activeDexFiles = new LinkedHashSet<>();
            if (unit.dex) for (String name : unit.owned) activeDexFiles.add(dex.origins.get(name).input.toPath());
            JSONObject result = sources(unit, new ArrayList<>(activeDexFiles), unit.dex ? dex : jars, unit.dex ? jars : dex, metadata, output, progress, factory, exported, stage, sourceUnits.size());
            stages.put(result);
            topLevel += result.getInt("topLevelClassesLoaded");
            generated += result.getInt("classesExported");
            if (unit.dex) visitedDexDefinitions += result.getInt("classDefinitionsVisited");
            errors += result.getInt("errors");
            warnings += result.getInt("warnings");
            System.gc();
            result.put("heapUsedAfterRelease", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            Runner.write(new File(output, "stages-progress.json"), new JSONObject().put("completedStages", stages));
        }
        if (!base.isSkipSources() && visitedDexDefinitions != dex.origins.size()) throw new IllegalStateException("Incomplete DEX coverage: " + visitedDexDefinitions + "/" + dex.origins.size());
        long resources;
        if (new File(output, "resources").isDirectory()) {
            try (java.util.stream.Stream<Path> files = Files.walk(new File(output, "resources").toPath())) { resources = files.filter(Files::isRegularFile).count(); }
        } else resources = 0;
        return new JSONObject().put("classes", topLevel).put("classesMeaning", "top-level classes loaded across source units").put("inputClassDefinitions", dex.origins.size() + jars.origins.size()).put("resources", resources).put("stages", stages).put("headerStages", headerStages).put("exportedClasses", generated).put("errors", errors).put("warnings", warnings).put("metadataLoadCount", units.size()).put("staging", "per-family-batch-load/compact-signature-index").put("sourceBatchClassTarget", SOURCE_BATCH_CLASS_TARGET).put("sourceBatchCount", sourceUnits.size()).put("visitedDexClassDefinitions", visitedDexDefinitions).put("allDexDefinitionsVisited", !base.isSkipSources() && visitedDexDefinitions == dex.origins.size()).put("sourceBatchTargetSemantics", "Planning size only; complete class families can exceed it and every class remains scheduled.").put("signatureIndexEntries", dex.types.size() + jars.types.size()).put("crossDexTypeSupport", "sibling signatures and parents; bytecode graphs stay within the active unit").put("crossDexFamilyRelocations", relocated).put("classpathAdapter", "jadx-1.5.6/ClspGraph.nameMap").put("completedStageStateReleased", true).put("stagingLimits", new JSONArray().put("Cross-unit method bodies are not retained together; whole-program inlining and transformations can differ from monolithic JADX."));
    }
}
