// AddNsoSegments — mappe .rodata et .data aux VA du NSO avant l'analyse.
//
// analyzeHeadless n'importe qu'un fichier : on charge text.bin à 0, puis ce
// prescript ajoute les deux autres segments aux adresses réelles. Sans eux les
// ADRP/ADD pointent dans le vide et le décompilateur n'affiche aucune chaîne —
// or c'est précisément ce qu'on vient lire.
//
// Chemins passés par -preScript AddNsoSegments <dir> ; <dir> contient
// rodata.bin et data.bin produits par nsoextract.
//
//@category NSO

import java.io.File;
import java.io.FileInputStream;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.MemoryBlock;

public class AddNsoSegments extends GhidraScript {

    private void mapBlock(String name, File f, long va, boolean write) throws Exception {
        if (!f.isFile()) {
            println("AddNsoSegments: absent -> " + f);
            return;
        }
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        Address addr = toAddr(va);
        if (currentProgram.getMemory().getBlock(addr) != null) {
            println("AddNsoSegments: " + name + " deja mappe");
            return;
        }
        MemoryBlock b = currentProgram.getMemory().createInitializedBlock(
                name, addr, data.length, (byte) 0, monitor, false);
        currentProgram.getMemory().setBytes(addr, data);
        b.setRead(true);
        b.setWrite(write);
        b.setExecute(false);
        println(String.format("AddNsoSegments: %s @ 0x%X (%d octets)", name, va, data.length));
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        // Segment addresses differ per game: nsoextract prints them (mem=0x...).
        // Borderlands GOTY: rodata 0x01DFA000, data 0x03178000.
        if (args.length < 3) {
            println("AddNsoSegments: usage <dir with rodata.bin/data.bin> <rodataVA hex> <dataVA hex>");
            return;
        }
        File dir = new File(args[0]);
        long rodataVA = Long.decode(args[1]);
        long dataVA = Long.decode(args[2]);
        mapBlock(".rodata", new File(dir, "rodata.bin"), rodataVA, false);
        mapBlock(".data", new File(dir, "data.bin"), dataVA, true);
    }
}
