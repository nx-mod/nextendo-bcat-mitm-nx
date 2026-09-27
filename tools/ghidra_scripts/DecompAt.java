// DecompAt — décompile la/les fonction(s) contenant les adresses données.
//
//@category NSO
//
// Usage : -postScript DecompAt <outfile> <addr hex> [addr hex ...]
//
// Si aucune fonction n'existe à l'adresse (analyse incomplète sur un blob brut),
// on en crée une à cet endroit avant de décompiler.

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;

public class DecompAt extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) {
            println("DecompAt: usage <outfile> <addr> [addr...]");
            return;
        }

        DecompInterface d = new DecompInterface();
        d.openProgram(currentProgram);

        try (PrintWriter out = new PrintWriter(new File(args[0]), "UTF-8")) {
            for (int i = 1; i < args.length; i++) {
                long va = Long.parseLong(args[i].replaceFirst("^0[xX]", ""), 16);
                Address addr = toAddr(va);

                Function fn = getFunctionContaining(addr);
                if (fn == null) {
                    disassemble(addr);
                    fn = createFunction(addr, null);
                }
                if (fn == null) {
                    out.printf("=== 0x%X : aucune fonction ===%n", va);
                    continue;
                }

                out.printf("=== 0x%X  fonction %s @ %s ===%n", va, fn.getName(), fn.getEntryPoint());
                DecompileResults res = d.decompileFunction(fn, 120, monitor);
                if (res != null && res.decompileCompleted()) {
                    out.println(res.getDecompiledFunction().getC());
                } else {
                    out.println("// echec decompilation: " + (res == null ? "null" : res.getErrorMessage()));
                }
                out.println();
                println(String.format("DecompAt: 0x%X -> %s", va, fn.getName()));
            }
        }
        d.dispose();
    }
}
