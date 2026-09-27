# Deploying nextendo-bcat-mitm-nx (prebuilt module)

`subsdk9` is the on-console module that makes bcat accept containers signed by
the Nextendo BCAT server.

1. Copy `subsdk9` to your SD card at:
   `sd:/atmosphere/contents/010000000000000C/exefs/subsdk9`
   (that title id is the bcat system module.)
2. **Do NOT** copy any `main.npdm` — leave bcat's own in place.
3. Reboot.

> IMPORTANT: this build ships in **discovery mode** — it logs bcat's layout but
> does not patch until you fill Nintendo's public modulus from your own console's
> bcat dump (see ../README.md "Providing the Nintendo modulus") and rebuild. The
> Nextendo key is already embedded.
