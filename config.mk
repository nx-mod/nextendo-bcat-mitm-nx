#----------------------------- User configuration -----------------------------

# Common settings
#------------------------

# How you're loading your module. Used to determine how to find the target module. (AsRtld/Module/Kip)
LOAD_KIND := Module

# Program you're targetting. Used to determine where to deploy your files.
# Set this to the target game's title id (must match config.json's title_id).
PROGRAM_ID := 010000000000000C

# Optional path to copy the final ELF to, for convenience.
ELF_EXTRACT :=

# Python command to use. Must be Python 3.4+.
PYTHON := python3

# JSON to use to make .npdm
NPDM_JSON := application.json

# Additional C/C++ flags to use.
C_FLAGS := 
CXX_FLAGS := 

# AsRtld settings
#------------------------

# Path to the SD card. Used to mount and deploy files on SD, likely with hekate UMS.
MOUNT_PATH := /mnt/k

# Module settings
#------------------------

# Settings for deploying over FTP. Used by the deploy-ftp.py script.
# Set FTP_IP to your console's address (sys-ftpd, port 5000 by default).
FTP_IP := 0.0.0.0
FTP_PORT := 5000
FTP_USERNAME := anonymous
FTP_PASSWORD :=

# Settings for deploying to Ryujinx/emulator. Used by the deploy-ryu.sh script.
# Set to your emulator's data directory (contains mods/, sdcard/).
RYU_PATH :=
