#include "lib.hpp"

#include <cstring>

#include "nextendo_bcat_key.h" /* kNextendoBcatModulus[256], kNextendoBcatExponent */

/*
    nextendo-bcat-mitm-nx — make a Switch accept BCAT delivery-cache containers
    signed by the Nextendo BCAT server (nextendo-bcat-nx) instead of Nintendo.

    A stock console verifies every BCAT/d4c container against an RSA-2048 public
    key baked into the bcat sysmodule. nextendo-bcat-nx signs containers with a
    LOCAL private key, so a stock console rejects them. This module loads into
    the bcat sysmodule and swaps that baked-in public modulus for ours
    (kNextendoBcatModulus, whose matching private key lives on the server), so
    the sysmodule's own, unmodified verify now accepts our containers. Nothing
    else about verification changes — this is the smallest, most version-robust
    hook: patch the data, not the code.

    THE ONE THING WE CANNOT SHIP: Nintendo's public modulus. It lives in the
    retail bcat NSO, which is copyrighted and console-specific. To find and
    replace it we need its 256 bytes as a search key. Fill kNintendoBcatModulus
    below from a dump of YOUR console's bcat module (see README "Providing the
    Nintendo modulus"). Until it is filled the module runs in DISCOVERY mode: it
    logs the bcat module layout and does not patch anything.

    Target: bcat sysmodule, program id 0100000000000010 (see config.json /
    config.mk). Log: svcOutputDebugString + sd:/config/nextendo-bcat-mitm-nx/log.txt.
*/

#define LOG(fmt, ...) Logging.Log("[bcat-mitm] " fmt, ##__VA_ARGS__)

/* Nintendo's baked-in BCAT public modulus (256 bytes, big-endian), the search
   key. All-zero by default => discovery mode (no patch). Provide it from a bcat
   NSO dump of your own console. */
static const unsigned char kNintendoBcatModulus[256] = { 0 };

namespace {

    bool AllZero(const unsigned char* p, size_t n) {
        for (size_t i = 0; i < n; i++)
            if (p[i] != 0)
                return false;
        return true;
    }

    /* Find `needle` (len bytes) within [start, start+size). Returns the address
       of the first match, or 0. */
    uintptr_t FindBytes(uintptr_t start, size_t size, const unsigned char* needle, size_t len) {
        if (size < len)
            return 0;
        const auto* hay = reinterpret_cast<const unsigned char*>(start);
        const size_t last = size - len;
        for (size_t i = 0; i <= last; i++) {
            if (hay[i] == needle[0] && std::memcmp(hay + i, needle, len) == 0)
                return start + i;
        }
        return 0;
    }

    /* Overwrite `len` bytes at read-only `roAddr` with `data`, via a temporary
       writable alias. */
    void PatchReadOnly(uintptr_t roAddr, const unsigned char* data, size_t len) {
        exl::util::RwPages block(roAddr, len);
        const uintptr_t rw = block.GetRw() + (roAddr - block.GetRo());
        std::memcpy(reinterpret_cast<void*>(rw), data, len);
        block.Flush();
    }

    /* Scan one module range for Nintendo's modulus and replace every occurrence
       with ours. Returns the number of patches applied. */
    int PatchRange(const char* label, const exl::util::Range& range) {
        int patched = 0;
        uintptr_t at = range.m_Start;
        size_t remaining = range.m_Size;
        for (;;) {
            uintptr_t hit = FindBytes(at, remaining, kNintendoBcatModulus, sizeof(kNintendoBcatModulus));
            if (hit == 0)
                break;
            PatchReadOnly(hit, kNextendoBcatModulus, sizeof(kNextendoBcatModulus));
            patched++;
            LOG("patched Nintendo modulus in %s @ main+0x%lx",
                label, hit - exl::util::modules::GetTargetStart());
            const uintptr_t next = hit + sizeof(kNintendoBcatModulus);
            remaining = range.GetEnd() - next;
            at = next;
        }
        return patched;
    }

    void ApplyKeySwap() {
        const auto& mi = exl::util::GetMainModuleInfo();
        LOG("bcat main module: base=0x%lx text=0x%lx(%lu) rodata=0x%lx(%lu) data=0x%lx(%lu)",
            mi.m_Total.m_Start,
            mi.m_Text.m_Start, mi.m_Text.m_Size,
            mi.m_Rodata.m_Start, mi.m_Rodata.m_Size,
            mi.m_Data.m_Start, mi.m_Data.m_Size);

        if (AllZero(kNintendoBcatModulus, sizeof(kNintendoBcatModulus))) {
            LOG("DISCOVERY mode: Nintendo modulus not provided, nothing patched. "
                "Dump this bcat NSO and fill kNintendoBcatModulus (see README).");
            return;
        }

        /* The key is const, so it lives in .rodata; scan .data too in case a
           build copies it. */
        int total = PatchRange("rodata", mi.m_Rodata) + PatchRange("data", mi.m_Data);
        if (total == 0)
            LOG("WARNING: Nintendo modulus not found in bcat — wrong dump, or a "
                "firmware where the key moved. No patch applied.");
        else
            LOG("done: %d modulus occurrence(s) swapped to the Nextendo key", total);
    }
}

/* Open the SD log once the sysmodule's fs allocator is up, then apply the swap.
   The swap itself needs no fs; doing it here just keeps the file log complete.
   Everything is also emitted over svcOutputDebugString, which works earlier. */
HOOK_DEFINE_TRAMPOLINE(MainHook) {
    static void Callback() {
        LOG("%s loaded into bcat: main @ 0x%lx", EXL_MODULE_NAME,
            exl::util::modules::GetTargetStart());
        ApplyKeySwap();
        Orig();
    }
};

extern "C" void nnMain();

extern "C" void exl_main(void* x0, void* x1) {
    exl::hook::Initialize();

    /* Apply immediately at load: the bcat module image (with its const modulus
       in .rodata) is already mapped by rtld before nnMain runs. Re-running in
       MainHook is harmless (idempotent: our bytes no longer match Nintendo's). */
    ApplyKeySwap();

    MainHook::InstallAtFuncPtr(nnMain);
}

extern "C" NORETURN void exl_exception_entry() {
    EXL_ABORT("Default exception handler called!");
}
