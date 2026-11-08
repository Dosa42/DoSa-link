import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;

public class NSPanelSeed extends GhidraScript {
    @Override
    public void run() throws Exception {
        int seeds = 0;
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isExecute() || !block.isInitialized()) continue;
            for (long offset = 0; offset + 3 <= block.getSize(); offset++) {
                monitor.checkCancelled();
                Address address = block.getStart().add(offset);
                if ((getByte(address) & 255) != 0x36 || (getByte(address.add(1)) & 15) != 1) continue;
                if (getFunctionContaining(address) != null) continue;
                disassemble(address);
                Instruction instruction = getInstructionAt(address);
                if (instruction != null && instruction.getMnemonicString().equalsIgnoreCase("entry")) {
                    if (createFunction(address, null) != null) seeds++;
                }
            }
        }
        println("Xtensa ENTRY function seeds: " + seeds);
    }
}
