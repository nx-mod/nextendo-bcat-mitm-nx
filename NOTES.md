# nextendo-bcat-mitm-nx — notes

## State

- **Builds** with `devkitpro/devkita64` → `out/subsdk9`. Verified in CI-equivalent
  (Docker) this session.
- The load → scan `.rodata`/`.data` → page-aliased overwrite mechanism is
  complete and reviewed.
- **Not console-verified.** The two things that need a real console:

### 1. Nintendo's modulus (required to patch at all)

`kNintendoBcatModulus` in `source/program/main.cpp` is zeroed → discovery mode.
It cannot be shipped (copyrighted, in the retail bcat NSO). Fill it from a dump
of your own console (README → "Providing the Nintendo modulus"). Discovery mode
logs bcat's `.rodata` range to help locate it.

### 2. Signature *scheme* must match the server (the real correctness risk)

Swapping the modulus only helps if the server signs containers the **same way**
bcat verifies them. `nextendo-bcat-nx` currently signs with **RSA-PSS / SHA-256,
salt length = hash length** over the header (minus the signature region) + the
payload — a *best guess*, because the exact d4c layout is internal to nn::bcat
and not fully published (see that repo's `container.go` / `NOTES.md`).

If bcat actually uses, say, PKCS#1 v1.5, or a different hash / signed region,
then our containers will fail verification **even with our key installed**. So
before trusting the pair, confirm from the bcat NSO (or a live capture) the:

- padding scheme (PSS vs PKCS#1 v1.5),
- hash,
- exact signed region and signature offset,

and align `nextendo-bcat-nx`'s `signContainer` to match. The modulus swap here is
scheme-agnostic; it is the server that must be brought into line.

## Deployment gotcha

Deploy **only** `subsdk9`, never the generated `main.npdm` (it is a generic
*application* npdm; bcat's own system npdm must stay). The module inherits bcat's
process permissions.

## Design choices

- **Data patch over code patch.** Overwriting one 256-byte constant is far more
  robust across firmware than hooking the verify function by offset (which moves
  every update). No `offsets.hpp` table to maintain.
- **Runs at `exl_main` and again in `MainHook`.** The bcat image (with the const
  modulus in `.rodata`) is mapped by rtld before `nnMain`, so the early pass
  usually suffices; the second pass is a harmless idempotent backstop (our bytes
  no longer match the search key).
- Built on the same exlaunch base as `exefs-hack-nx`, so fixes to the framework
  carry across.

## If you want a code-patch fallback instead

Should the modulus not be a plain contiguous constant on some firmware, the
alternative is to hook the verify function and force success (accepting only our
containers, which arrive over DNS.mitm from our server). That needs the function
offset per build id — put it in `offsets.hpp` and add an `InlineHook`. Left out
for now because the data patch is cleaner and version-robust.
