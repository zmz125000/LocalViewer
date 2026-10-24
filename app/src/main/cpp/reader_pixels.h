#pragma once

#include <cstdint>
#include <vector>

// A synchronous reader decode may retain the already packed vector instead of
// copying it into a Java array. Export captures keep straight alpha through encode.
bool retain_reader_pixels(std::vector<uint8_t>& pixels);
bool retain_reader_pixels(std::vector<uint16_t>& pixels);
bool export_pixels_capture();
bool export_pixels_keep_sdr();
