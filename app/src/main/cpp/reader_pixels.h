#pragma once

#include <cstdint>
#include <vector>

// A synchronous reader decode may retain the already packed vector instead of
// copying it into a Java array. Export calls have no capture and keep their ABI.
bool retain_reader_pixels(std::vector<uint8_t>& pixels);
bool retain_reader_pixels(std::vector<uint16_t>& pixels);
