// nsoextract — décompresse un NSO0 Switch en segments bruts (.text/.rodata/.data).
//
// Le format NSO empile trois segments compressés en blocs LZ4 « raw » (pas de
// frame, pas de checksum), ce qui évite toute dépendance : le décodeur tient en
// une soixantaine de lignes plus bas.
//
//	nsoextract <main.nso> <dossier de sortie>
//
// Écrit <out>/text.bin, rodata.bin, data.bin, plus un strings.txt (ASCII >= 6)
// du rodata — c'est là que vivent les noms de service et d'erreur Demonware.
package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

type segment struct {
	fileOff  uint32
	memOff   uint32
	decompSz uint32
	compSz   uint32
	name     string
}

// lz4Decompress décode un bloc LZ4 brut. Format : un token par séquence, quartet
// haut = longueur littérale, quartet bas = longueur de match (0x0F = « lire la
// suite par octets jusqu'à != 0xFF »), puis offset de match sur 2 octets LE.
func lz4Decompress(src []byte, maxOut int) ([]byte, error) {
	dst := make([]byte, 0, maxOut)
	i := 0
	readLen := func(n int) (int, error) {
		if n != 0x0F {
			return n, nil
		}
		for {
			if i >= len(src) {
				return 0, errors.New("lz4: troncature en longueur")
			}
			b := int(src[i])
			i++
			n += b
			if b != 0xFF {
				return n, nil
			}
		}
	}

	for i < len(src) {
		token := src[i]
		i++

		litLen, err := readLen(int(token >> 4))
		if err != nil {
			return nil, err
		}
		if i+litLen > len(src) {
			return nil, errors.New("lz4: littéraux hors limites")
		}
		dst = append(dst, src[i:i+litLen]...)
		i += litLen

		// Le dernier bloc se termine sur des littéraux, sans séquence de match.
		if i >= len(src) {
			break
		}
		if i+2 > len(src) {
			return nil, errors.New("lz4: offset tronqué")
		}
		offset := int(binary.LittleEndian.Uint16(src[i:]))
		i += 2
		if offset == 0 || offset > len(dst) {
			return nil, fmt.Errorf("lz4: offset invalide %d", offset)
		}

		matchLen, err := readLen(int(token & 0x0F))
		if err != nil {
			return nil, err
		}
		matchLen += 4 // minmatch

		// Recopie octet par octet : les matches peuvent se chevaucher (RLE).
		start := len(dst) - offset
		for n := 0; n < matchLen; n++ {
			dst = append(dst, dst[start+n])
		}
	}
	return dst, nil
}

func asciiStrings(b []byte, min int) []string {
	var out []string
	var cur strings.Builder
	flush := func() {
		if cur.Len() >= min {
			out = append(out, cur.String())
		}
		cur.Reset()
	}
	for _, c := range b {
		if c >= 0x20 && c < 0x7F && unicode.IsPrint(rune(c)) {
			cur.WriteByte(c)
			continue
		}
		flush()
	}
	flush()
	return out
}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: nsoextract <main.nso> <outdir>")
		os.Exit(2)
	}
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if len(raw) < 0x100 || string(raw[0:4]) != "NSO0" {
		fmt.Fprintln(os.Stderr, "pas un NSO0")
		os.Exit(1)
	}
	outDir := os.Args[2]
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	flags := binary.LittleEndian.Uint32(raw[0x0C:])
	segs := []segment{
		{name: "text"}, {name: "rodata"}, {name: "data"},
	}
	for n := range segs {
		h := 0x10 + n*0x10
		segs[n].fileOff = binary.LittleEndian.Uint32(raw[h:])
		segs[n].memOff = binary.LittleEndian.Uint32(raw[h+4:])
		segs[n].decompSz = binary.LittleEndian.Uint32(raw[h+8:])
		segs[n].compSz = binary.LittleEndian.Uint32(raw[0x60+n*4:])
	}

	for n, s := range segs {
		if s.fileOff == 0 || s.compSz == 0 {
			fmt.Printf("%-7s absent\n", s.name)
			continue
		}
		end := int(s.fileOff) + int(s.compSz)
		if end > len(raw) {
			fmt.Printf("%-7s hors fichier\n", s.name)
			continue
		}
		blob := raw[s.fileOff:end]

		if flags&(1<<uint(n)) != 0 {
			blob, err = lz4Decompress(blob, int(s.decompSz))
			if err != nil {
				fmt.Fprintf(os.Stderr, "%s: %v\n", s.name, err)
				continue
			}
		}
		path := filepath.Join(outDir, s.name+".bin")
		if err := os.WriteFile(path, blob, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			continue
		}
		fmt.Printf("%-7s mem=0x%08X  %9d octets -> %s\n", s.name, s.memOff, len(blob), path)

		if s.name == "rodata" {
			strs := asciiStrings(blob, 6)
			f, err := os.Create(filepath.Join(outDir, "strings.txt"))
			if err == nil {
				for _, v := range strs {
					fmt.Fprintln(f, v)
				}
				f.Close()
				fmt.Printf("        %d chaînes ASCII -> strings.txt\n", len(strs))
			}
		}
	}
}
