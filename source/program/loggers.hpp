#pragma once

#include <lib/log/svc_logger.hpp>
#include <program/file_logger.hpp>

/* Specify logger implementations here. */
inline exl::log::LoggerMgr<
    exl::log::SvcLogger,
    exl::log::FileLogger
> Logging;
