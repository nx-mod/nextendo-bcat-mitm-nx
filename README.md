# nextendo-bcat-mitm-nx

*(still in alpha testing)*

**A new console module by nx-mod** for the Nextendo Network.

An **exlaunch** module that makes a Nintendo Switch accept BCAT delivery-cache
containers signed by the Nextendo BCAT server ([`nextendo-bcat-nx`](https://github.com/nx-mod/nextendo-bcat-nx))
instead of Nintendo's.

It is the on-console half of the BCAT pair:

```
nextendo-bcat-nx  ──signs containers with our PRIVATE key──▶  console
nextendo-bcat-mitm-nx  ──swaps the PUBLIC key inside bcat──▶  container accepted
```

## Why it exists

A stock console verifies every BCAT/d4c container against an RSA-2048 public key
baked into the **bcat** system module (program id `010000000000000C`). Our server
signs containers with a *local* private key, so a stock console rejects them
(`2122-2403`, module 122 = bcat). This module loads into the bcat sysmodule and
overwrites that baked-in public modulus with **ours** (the one whose private key
lives on the server), so bcat's own, unmodified verification now accepts our
containers. It patches **data, not code** — the smallest, most firmware-robust
change: no function offsets to chase per update, just one 256-byte constant.

## How it works

At load (and again once bcat's `nnMain` runs, idempotently) the module:

1. locates bcat's main module in memory (`.rodata` / `.data`);
2. searches it for Nintendo's public modulus (256 bytes);
3. overwrites each occurrence with the Nextendo modulus
   (`source/program/nextendo_bcat_key.h`), via a temporary writable page alias.

Logs go to `svcOutputDebugString` (and, where available,
`sd:/config/nextendo-bcat-mitm-nx/log.txt`).

## Providing the Nintendo modulus

**The one thing this repo cannot ship is Nintendo's public modulus** — it lives
in the copyrighted, console-specific retail bcat NSO. Without its 256 bytes as a
search key the module runs in **discovery mode**: it logs bcat's module layout
and patches nothing.

To finish it, dump your own console's bcat module and paste its public modulus
into `kNintendoBcatModulus` in `source/program/main.cpp`:

1. Get the bcat NSO (e.g. `nxdumptool` → system module, or from a firmware dump).
2. Find the RSA-2048 public modulus it uses for delivery-cache verification
   (256 bytes, big-endian) — Ghidra on the verify path, or the discovery log to
   narrow the `.rodata` region.
3. Fill `kNintendoBcatModulus[256]` with those bytes and rebuild.

The Nextendo modulus is already embedded; the matching **private** key is on the
server (`nextendo-bcat-nx`, `BCAT_KEY_FILE`). They are a real, generated pair.

## Building

```sh
# with Docker (no local devkitPro needed):
docker run --rm -e HOME=/tmp -v "$PWD:/work" -w /work devkitpro/devkita64 \
  bash -c 'make -j$(nproc)'
# -> out/subsdk9  (+ out/main.npdm — see the deploy warning below)
```

## Deploying

Copy **only** `out/subsdk9` to your SD card at:

```
sd:/atmosphere/contents/010000000000000C/exefs/subsdk9
```

> ⚠️ **Do NOT copy the generated `main.npdm`.** The build emits a generic
> *application* npdm; overwriting bcat's own system npdm would change its
> permissions and break the sysmodule. The module inherits bcat's process
> permissions (which already include the syscalls it uses), so only the
> `subsdk9` is needed. Leave bcat's `main.npdm` alone.

Reboot. Under Nextendo mode (DNS.mitm pointing BCAT hosts at the server), bcat
will now accept the server's containers.

## Status

The mechanism (module-load, memory scan, page-aliased patch) is complete and
**compiles**. It is not yet console-verified — see [`NOTES.md`](NOTES.md) for the
open items, most importantly confirming that the server's signature *scheme*
(RSA-PSS/SHA-256 today) matches what bcat actually checks, not just the key.

## Credits / sources

- **exlaunch** — the module framework (shared with
  [`exefs-hack-nx`](https://github.com/nx-mod/exefs-hack-nx)), by shadowninja108.
- **Atmosphère** — exefs override / `contents/<program_id>/exefs`.
- BCAT program id and container format: switchbrew (BCAT services, Title list).

## Licence

Follows the exlaunch framework's licence (see `LICENSE`). Not affiliated with
Nintendo. Use it on hardware you own.

## Credits

Built by nx-mod for the **Nextendo Network**, on the work of the Nextendo Network team — https://nextendo.network. Nextendo is awesome.
