#pragma once
#include <cstddef>
#include <cstdint>

// Fixed-configuration APAC audio and single-preroll synchronization packets.
// Offsets describe core payloads; ancillary scene/DRC data is not parsed here.
struct DiPlayApacPacket {
    unsigned type = 0;
    size_t audioBitOffset = 0;
    size_t prerollOffset = 0;
    size_t prerollBytes = 0;

    static bool parse(const uint8_t* packet, size_t bytes, DiPlayApacPacket& result) {
        if (!packet || !bytes || bytes > 65536) return false;
        DiPlayApacPacket parsed;
        parsed.type = packet[0] >> 6;
        if (parsed.type == 1) {
            parsed.audioBitOffset = 2;
        } else if (parsed.type == 2) {
            if (packet[0] & 0x20) return false; // configuration changes unsupported
            unsigned count = (packet[0] >> 3) & 3;
            if (count > 1) return false;
            parsed.audioBitOffset = 5;
            if (count == 1) {
                auto read16 = [&](uint32_t& value) {
                    if (bytes * 8 - parsed.audioBitOffset < 16) return false;
                    value = 0;
                    for (unsigned i = 0; i < 16; i++, parsed.audioBitOffset++) {
                        size_t bit = parsed.audioBitOffset;
                        value = (value << 1) | ((packet[bit / 8] >> (7 - bit % 8)) & 1);
                    }
                    return true;
                };
                uint32_t length;
                if (!read16(length)) return false;
                if (length == 65535) {
                    uint32_t extra;
                    if (!read16(extra)) return false;
                    length += extra;
                }
                parsed.prerollOffset = (parsed.audioBitOffset + 7) / 8;
                // At least one byte of current audio must follow the preroll.
                if (!length || length >= bytes - parsed.prerollOffset) return false;
                if (packet[parsed.prerollOffset] >> 6 != 1) return false;
                parsed.prerollBytes = length;
                parsed.audioBitOffset = (parsed.prerollOffset + length) * 8;
            }
        } else return false;
        result = parsed;
        return true;
    }
};
