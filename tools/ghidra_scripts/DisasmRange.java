// DisasmRange — listing d'assembleur brut sur une plage, plus décompilation
// depuis le VRAI début de fonction.
//
//@category NSO
//
// Usage : -postScript DisasmRange <outfile> <start hex> <end hex> [<start> <end> ...]
//
// DecompAt crée une fonction à l'adresse demandée quand l'analyse n'a pas été
// faite ; si l'adresse tombe au milieu d'une fonction, le décompilateur voit un
// registre callee-saved jamais initialisé (unaff_x19...) et perd le contexte.
// Ici on remonte d'abord jusqu'au prologue (stp x29,x30,[sp,#-N]! ou
// sub sp,sp,#N suivi d'un stp), on désassemble tout ce qui est demandé en
// suivant le flot, et on imprime chaque instruction avec ses cibles de branche
// et les références ADRP+ADD résolues, pour pouvoir lire les tables de saut.

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;

public class DisasmRange extends GhidraScript {

    // stp x29, x30, [sp, #-N]!  => masque 0xFFC07FFF, valeur 0xA9807BFD
    private static boolean isPreIndexFpLr(int w) {
        return (w & 0xFFC07FFF) == 0xA9807BFD;
    }

    // sub sp, sp, #imm  => 0xD10003FF masque 0xFF0003FF
    private static boolean isSubSp(int w) {
        return (w & 0xFF0003FF) == 0xD10003FF;
    }

    // ret / b (inconditionnel) marquent la fin de la fonction précédente
    private static boolean isRetOrB(int w) {
        return w == 0xD65F03C0 || (w & 0xFC000000) == 0x14000000;
    }

    // Entrees de fonction connues : toute cible de BL en est une. C'est plus
    // fiable que la recherche de prologue, qui rate les fonctions commencant
    // par stp x2X,x2Y ou str xN,[sp,#-N]! et remonte alors dans la precedente.
    private java.util.TreeSet<Long> blTargets;

    private void collectBlTargets() throws Exception {
        blTargets = new java.util.TreeSet<>();
        Memory mem = currentProgram.getMemory();
        ghidra.program.model.mem.MemoryBlock text = mem.getBlock(toAddr(0));
        long end = text.getEnd().getOffset();
        byte[] buf = new byte[(int) (end + 1)];
        mem.getBytes(toAddr(0), buf);
        for (int i = 0; i + 4 <= buf.length; i += 4) {
            int w = (buf[i] & 0xff) | (buf[i + 1] & 0xff) << 8 | (buf[i + 2] & 0xff) << 16 | (buf[i + 3] & 0xff) << 24;
            if ((w & 0xFC000000) == 0x94000000) {
                int imm = w & 0x03FFFFFF;
                if ((imm & 0x02000000) != 0) {
                    imm -= 0x04000000;
                }
                long t = i + (long) imm * 4;
                if (t >= 0 && t <= end) {
                    blTargets.add(t);
                }
            }
        }
    }

    private Address findStart(Address a) throws Exception {
        if (blTargets == null) {
            collectBlTargets();
        }
        Long entry = blTargets.floor(a.getOffset());
        if (entry != null && a.getOffset() - entry < 0x4000) {
            return toAddr(entry);
        }
        Memory mem = currentProgram.getMemory();
        Address p = a;
        for (int i = 0; i < 0x4000; i++) {
            int w = mem.getInt(p);
            if (isPreIndexFpLr(w) || isSubSp(w)) {
                int prev = mem.getInt(p.subtract(4));
                // Un prologue ne compte que s'il suit la fin d'une autre
                // fonction (ret, b, ou bourrage) ; sinon c'est un sub sp
                // au milieu du corps.
                if (isRetOrB(prev) || prev == 0) {
                    return p;
                }
            }
            p = p.subtract(4);
        }
        return a;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3 || (args.length - 1) % 2 != 0) {
            println("DisasmRange: usage <outfile> <start> <end> [<start> <end>...]");
            return;
        }
        DecompInterface d = new DecompInterface();
        d.openProgram(currentProgram);

        try (PrintWriter out = new PrintWriter(new File(args[0]), "UTF-8")) {
            for (int i = 1; i < args.length; i += 2) {
                long s = Long.parseLong(args[i].replaceFirst("^0[xX]", ""), 16);
                long e = Long.parseLong(args[i + 1].replaceFirst("^0[xX]", ""), 16);
                Address sa = toAddr(s);
                Address ea = toAddr(e);

                Address fstart = findStart(sa);
                AddressSet set = new AddressSet(fstart, ea);
                new DisassembleCommand(set, set, true).applyTo(currentProgram, monitor);

                out.printf("##### range 0x%X-0x%X  (fonction commence a %s)%n", s, e, fstart);
                Instruction ins = getInstructionAt(fstart);
                if (ins == null) {
                    ins = getInstructionAfter(fstart);
                }
                while (ins != null && ins.getAddress().compareTo(ea) <= 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("%s  %08x  %-8s ", ins.getAddress(),
                            currentProgram.getMemory().getInt(ins.getAddress()), ins.getMnemonicString()));
                    for (int k = 0; k < ins.getNumOperands(); k++) {
                        if (k > 0) {
                            sb.append(", ");
                        }
                        sb.append(ins.getDefaultOperandRepresentation(k));
                    }
                    for (Reference r : ins.getReferencesFrom()) {
                        sb.append("   ; -> ").append(r.getToAddress());
                    }
                    out.println(sb);
                    ins = getInstructionAfter(ins.getAddress());
                }
                out.println();

                Function fn = getFunctionAt(fstart);
                if (fn == null) {
                    fn = createFunction(fstart, null);
                }
                if (fn != null) {
                    out.printf("=== decompilation depuis %s ===%n", fstart);
                    DecompileResults res = d.decompileFunction(fn, 180, monitor);
                    if (res != null && res.decompileCompleted()) {
                        out.println(res.getDecompiledFunction().getC());
                    } else {
                        out.println("// echec: " + (res == null ? "null" : res.getErrorMessage()));
                    }
                }
                out.println();
                println(String.format("DisasmRange: 0x%X-0x%X depuis %s", s, e, fstart));
            }
        }
        d.dispose();
    }
}
