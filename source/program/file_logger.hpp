#pragma once

#include <lib/log/ilogger.hpp>

namespace exl::log {

    /* Appends every log line to sd:/config/<EXL_MODULE_NAME>/log.txt through the game's
       own nn::fs. Lines logged before file::Open() succeeds only reach the other loggers. */
    struct FileLogger : public ILogger {
        virtual void LogRaw(std::string_view string) final;
    };

    namespace file {
        /* Mount sd:, create sd:/config/<module name> and start a fresh log.txt. Safe to call twice. */
        bool Open();
    }
}
