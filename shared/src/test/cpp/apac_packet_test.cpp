#include "apac_packet.h"
#include <cassert>
#include <vector>

static bool parse(const std::vector<uint8_t>& bytes, DiPlayApacPacket& packet) {
    return DiPlayApacPacket::parse(bytes.data(), bytes.size(), packet);
}

int main() {
    DiPlayApacPacket packet;
    assert(parse({0x40, 0x00}, packet));
    assert(packet.type == 1 && packet.audioBitOffset == 2 && !packet.prerollBytes);
    // Apple-generated first packets: zero preroll, core starts at bit 5.
    assert(parse({0x80, 0x00, 0x3c, 0x98, 0x00}, packet));
    assert(packet.type == 2 && packet.audioBitOffset == 5 && !packet.prerollBytes);
    assert(parse({0x80, 0x00, 0x26, 0x12, 0x21, 0xe0, 0x00}, packet));

    // Observed 5.1 / 7.1.4 ASP headers encode 407 / 806 BYTES at bit 5.
    for (auto length : {407u, 806u}) {
        std::vector<uint8_t> bytes(3 + length + 1);
        bytes[0] = 0x88 | (length >> 13);
        bytes[1] = length >> 5;
        bytes[2] = length << 3;
        bytes[3] = 0x40; // nested normal frame
        bytes.back() = 0x8a; // current core has no second frame-type prefix
        assert(parse(bytes, packet));
        assert(packet.type == 2 && packet.prerollOffset == 3);
        assert(packet.prerollBytes == length && packet.audioBitOffset == (3 + length) * 8);
        bytes[3] = 0x80;
        assert(!parse(bytes, packet)); // nested ASP
        bytes[3] = 0;
        assert(!parse(bytes, packet)); // unsupported nested frame type
        bytes[3] = 0xc0;
        assert(!parse(bytes, packet));
        bytes[3] = 0x40;
        bytes.pop_back();
        assert(!parse(bytes, packet)); // no current core
        bytes.pop_back();
        assert(!parse(bytes, packet)); // truncated preroll
    }

    assert(!DiPlayApacPacket::parse(nullptr, 1, packet));
    assert(!parse({}, packet));
    assert(!parse({0x00}, packet));
    assert(!parse({0xc0}, packet));
    assert(!parse({0xa0, 0}, packet)); // reconfiguration
    assert(!parse({0x90, 0}, packet)); // two prerolls
    assert(!parse({0x98, 0}, packet)); // three prerolls
    assert(!parse({0x88}, packet));
    assert(!parse({0x88, 0}, packet));
    assert(!parse({0x88, 0, 0, 0x40, 0}, packet)); // zero-byte preroll
    assert(!parse({0x8f, 0xff, 0xf8}, packet)); // truncated extended size
    assert(!parse({0x8f, 0xff, 0xff, 0xff, 0xf8, 0x40}, packet)); // oversized
    std::vector<uint8_t> maximum(65536, 0);
    maximum[0] = 0x8f; maximum[1] = 0xff; maximum[2] = 0xd0; // 65530 bytes
    maximum[3] = 0x40;
    assert(parse(maximum, packet));
    assert(packet.prerollBytes == 65530 && packet.audioBitOffset == 65533 * 8);
    maximum.push_back(0);
    assert(!parse(maximum, packet));
}
