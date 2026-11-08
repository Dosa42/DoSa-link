import com.google.gson.GsonBuilder;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
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

public class NSPanelTrace extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        Set<Function> functions = new LinkedHashSet<>();
        Map<String, String> requested = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            Address address = toAddr(args[i]);
            Function function = getFunctionAt(address);
            if (function == null) function = getFunctionContaining(address);
            if (function == null) {
                disassemble(address);
                function = createFunction(address, null);
            }
            if (function == null) throw new IllegalArgumentException(args[i]);
            functions.add(function);
            requested.put(args[i], function.getEntryPoint().toString());
        }
        Map<String, Object> readers = new LinkedHashMap<>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.isExecute()) continue;
            byte[] bytes = new byte[(int) block.getSize()];
            block.getBytes(block.getStart(), bytes);
            String data = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String name : new String[]{"esp_partition_read", "spi_flash_read", "spi_flash_read_encrypted"}) {
                int offset = data.indexOf(name + "\u0000");
                if (offset < 0) continue;
                Address target = block.getStart().add(offset);
                Set<String> owners = new LinkedHashSet<>();
                ReferenceIterator first = currentProgram.getReferenceManager().getReferencesTo(target);
                while (first.hasNext()) {
                    Address from = first.next().getFromAddress();
                    ReferenceIterator second = currentProgram.getReferenceManager().getReferencesTo(from);
                    while (second.hasNext()) {
                        Function owner = getFunctionContaining(second.next().getFromAddress());
                        if (owner != null) {
                            owners.add(owner.getEntryPoint().toString());
                            functions.add(owner);
                        }
                    }
                    Function owner = getFunctionContaining(from);
                    if (owner != null) {
                        owners.add(owner.getEntryPoint().toString());
                        functions.add(owner);
                    }
                }
                readers.put(name, Map.of("stringAddress", target.toString(), "functions", owners));
            }
        }
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        List<Object> output = new ArrayList<>();
        try {
            for (Function function : functions) {
                DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
                List<String> callers = new ArrayList<>();
                for (Function caller : function.getCallingFunctions(monitor)) callers.add(caller.getEntryPoint().toString());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("address", function.getEntryPoint().toString());
                row.put("callers", callers);
                row.put("completed", result.decompileCompleted());
                row.put("error", result.getErrorMessage());
                row.put("code", result.getDecompiledFunction() == null ? null : result.getDecompiledFunction().getC());
                output.add(row);
            }
        } finally {
            decompiler.dispose();
        }
        Files.writeString(Path.of(args[0], "controller-trace.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("readers", readers, "requested", requested, "functions", output)));
        println("Exported " + output.size() + " callback and reader functions from existing project");
    }
}
