import com.google.gson.GsonBuilder;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class NSPanelExport extends GhidraScript {
    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]);
        return result;
    }

    private void collect(Address target, List<Object> references, Set<Function> functions, int depth) {
        ReferenceIterator iterator = currentProgram.getReferenceManager().getReferencesTo(target);
        while (iterator.hasNext()) {
            Reference reference = iterator.next();
            Address from = reference.getFromAddress();
            Function function = getFunctionContaining(from);
            references.add(row("from", from.toString(), "to", target.toString(), "type", reference.getReferenceType().toString(), "function", function == null ? null : function.getEntryPoint().toString()));
            if (function != null) functions.add(function);
            else if (depth > 0) collect(from, references, functions, depth - 1);
        }
    }

    @Override
    public void run() throws Exception {
        Path output = Path.of(getScriptArgs()[0]);
        Pattern pattern = Pattern.compile("(?i)ota|upgrad|firmware|flash|nsp_https|nspanel_main|whmi|FWPNL|zeroconf|readback|read_flash|read_data|read_partition|/update|/flash|update.bin|Range:");
        List<Object> strings = new ArrayList<>();
        Set<Function> focus = new LinkedHashSet<>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.isExecute()) continue;
            byte[] bytes = new byte[(int) block.getSize()];
            block.getBytes(block.getStart(), bytes);
            for (int start = 0; start < bytes.length;) {
                int end = start;
                while (end < bytes.length && (bytes[end] >= 32 && bytes[end] <= 126 || bytes[end] == 10 || bytes[end] == 13 || bytes[end] == 9)) end++;
                if (end - start >= 5 && end < bytes.length && bytes[end] == 0) {
                    String value = new String(bytes, start, end - start, StandardCharsets.US_ASCII);
                    if (pattern.matcher(value).find()) {
                        List<Object> references = new ArrayList<>();
                        Address address = block.getStart().add(start);
                        collect(address, references, focus, 2);
                        strings.add(row("address", address.toString(), "text", value, "references", references));
                    }
                }
                start = end + 1;
            }
        }
        List<Object> inventory = new ArrayList<>();
        FunctionIterator all = currentProgram.getFunctionManager().getFunctions(true);
        while (all.hasNext()) {
            Function function = all.next();
            inventory.add(row("address", function.getEntryPoint().toString(), "name", function.getName(), "bytes", function.getBody().getNumAddresses()));
        }
        List<Function> primary = new ArrayList<>(focus);
        for (Function function : primary) {
            for (Function caller : function.getCallingFunctions(monitor)) focus.add(caller);
        }
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        List<Object> decompiled = new ArrayList<>();
        try {
            for (Function function : focus) {
                monitor.checkCancelled();
                DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
                List<String> callers = new ArrayList<>();
                for (Function caller : function.getCallingFunctions(monitor)) callers.add(caller.getEntryPoint().toString());
                List<String> callees = new ArrayList<>();
                for (Function callee : function.getCalledFunctions(monitor)) callees.add(callee.getEntryPoint().toString());
                String code = result.decompileCompleted() && result.getDecompiledFunction() != null ? result.getDecompiledFunction().getC() : null;
                decompiled.add(row("address", function.getEntryPoint().toString(), "name", function.getName(), "callers", callers, "callees", callees, "completed", result.decompileCompleted(), "error", result.getErrorMessage(), "code", code));
            }
        } finally {
            decompiler.dispose();
        }
        Map<String, Object> report = row("program", currentProgram.getName(), "language", currentProgram.getLanguageID().toString(), "compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString(), "functionCount", inventory.size(), "anchorCount", strings.size(), "primaryFunctionCount", primary.size(), "focusFunctionCount", focus.size(), "strings", strings, "functions", inventory, "decompiled", decompiled, "scope", "Offline static recovered vendor controller; no panel execution and no installed-version match");
        Files.writeString(output.resolve("controller-analysis.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        println("Exported " + strings.size() + " anchors and " + decompiled.size() + " focused decompilations");
    }
}
