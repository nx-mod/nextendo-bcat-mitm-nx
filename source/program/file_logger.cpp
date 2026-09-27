#include "file_logger.hpp"

#include <atomic>
#include <cstring>
#include <lib.hpp>
#include <nn/fs.hpp>

#include "setting.hpp"

/* Not declared in the bundled nn::fs headers; resolved from the SDK at load. */
namespace nn::fs {
    Result DeleteFile(char const* path);
}

namespace exl::log {

    namespace {
        /* The log lives under sd:/config/<module name>/, so a fork gets its own
           directory automatically by changing EXL_MODULE_NAME in setting.hpp. */
        constexpr char LogDir[]  = "sd:/config/" EXL_MODULE_NAME;
        constexpr char LogPath[] = "sd:/config/" EXL_MODULE_NAME "/log.txt";

        bool s_Open = false;
        nn::fs::FileHandle s_Handle {};
        s64 s_Offset = 0;
        /* Hooks log from any game thread; writes are short, so a spin lock is enough. */
        std::atomic_flag s_Lock = ATOMIC_FLAG_INIT;

        /* Lines logged before the file can be opened (nn::fs is off limits until the game
           installs its allocator) wait here and are written first. */
        constexpr size_t PendingSize = 16 * 1024;
        char s_Pending[PendingSize];
        size_t s_PendingLength = 0;

        void Lock() {
            while (s_Lock.test_and_set(std::memory_order_acquire)) {}
        }

        void Unlock() {
            s_Lock.clear(std::memory_order_release);
        }

        void WriteLocked(const char* data, size_t size) {
            /* Flush every write: the log is most useful right before a crash. */
            auto option = nn::fs::WriteOption::CreateOption(nn::fs::WriteOptionFlag_Flush);
            if (R_SUCCEEDED(nn::fs::WriteFile(s_Handle, s_Offset, data, size, option)))
                s_Offset += size;
        }

        void PendLocked(const char* data, size_t size) {
            if (s_PendingLength + size > PendingSize)
                return;
            std::memcpy(s_Pending + s_PendingLength, data, size);
            s_PendingLength += size;
        }
    }

    namespace file {
        bool Open() {
            if (s_Open)
                return true;

            /* Fails harmlessly if something already mounted "sd"; the calls below say whether it is usable. */
            nn::fs::MountSdCardForDebug("sd");
            nn::fs::CreateDirectory("sd:/config");
            nn::fs::CreateDirectory(LogDir);
            nn::fs::DeleteFile(LogPath);

            if (R_FAILED(nn::fs::CreateFile(LogPath, 0)))
                return false;
            if (R_FAILED(nn::fs::OpenFile(&s_Handle, LogPath, nn::fs::OpenMode_Write | nn::fs::OpenMode_Append)))
                return false;

            Lock();
            s_Offset = 0;
            if (s_PendingLength != 0)
                WriteLocked(s_Pending, s_PendingLength);
            s_PendingLength = 0;
            s_Open = true;
            Unlock();
            return true;
        }
    }

    void FileLogger::LogRaw(std::string_view string) {
        if (string.empty())
            return;

        const bool needsNewline = string.back() != '\n';
        Lock();
        if (s_Open) {
            WriteLocked(string.data(), string.size());
            if (needsNewline)
                WriteLocked("\n", 1);
        } else {
            PendLocked(string.data(), string.size());
            if (needsNewline)
                PendLocked("\n", 1);
        }
        Unlock();
    }
}
