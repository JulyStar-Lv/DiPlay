#pragma once
#include <cstdint>
#include <cstring>
#include <cmath>
#include <memory>
#include <vector>
#include "apac_packet.h"
#include "apac/config.h"
#include "apac/decoder.h"

// Experimental fixed speaker layouts only. The upstream port does not implement
// scene rendering or loudness/DRC. APAP trim and conservative gain are applied by Kotlin.
class DiPlayApacChannelDecoder {
    apac::GlobalConfig config{};
    apac::CoreDecoder core;
    apac::LRVQDecoder lrvq;
    apac::BWELPC bwe;
    bool ready = false;
    static uint32_t be32(const uint8_t* p) {
        return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3];
    }
public:
    int channels = 0;
    uint32_t layout = 0;
    bool configure(const uint8_t* cookie, size_t length) {
        if (length < 15 || length > 4096) return false;
        size_t at = 0;
        while (at + 8 <= length) {
            uint32_t bytes = be32(cookie + at);
            if (bytes < 8 || bytes > length - at) return false;
            if (!memcmp(cookie + at + 4, "dapa", 4)) {
                if (bytes < 15 || cookie[at + 12] != 8 || cookie[at + 13] != 0) return false;
                apac::BitstreamReader bits(cookie + at + 14, bytes - 14);
                config.Reset();
                uint32_t result = config.Deserialize(bits);
                if (result != apac::kOk && result != apac::kPortNeedsAudioScene &&
                    result != apac::kPortNeedsLoudnessDRC) return false;
                layout = config.layout.tag;
                if (config.profileLevel.profile != 31 || config.profileLevel.level > 6 || config.sampleRate != 48000 ||
                    config.frameLength != 1024 || config.numCodecConfigs != 1 || config.hasASCConfigData) return false;
                if (layout != 0x790006 && layout != 0x7c0006 && layout != 0xc0000c) return false;
                channels = config.numChannels;
                if (channels != int(layout & 65535) || config.codecConfigs[0].NumChannels() != unsigned(channels) ||
                    config.codecConfigs[0].channelOffset != 0) return false;
                ready = core.Initialize(config.codecConfigs[0], 48000, 1024, &lrvq, &bwe) == apac::kOk;
                return ready;
            }
            // frma and apac wrappers contain nested atoms; accept only their documented prefixes.
            if (!memcmp(cookie + at + 4, "frma", 4)) {
                if (bytes != 12 || memcmp(cookie + at + 8, "apac", 4)) return false;
                at += bytes;
            } else if (!memcmp(cookie + at + 4, "apac", 4)) {
                if (bytes < 12 || be32(cookie + at + 8) != 0) return false;
                at += 12;
            } else return false;
        }
        return false;
    }
    bool decode(const uint8_t* packet, size_t bytes, std::vector<uint8_t>& pcm) {
        DiPlayApacPacket parsed;
        if (!ready || !DiPlayApacPacket::parse(packet, bytes, parsed)) return false;
        float* decoded[apac::kMaxDecoderChannels] = {};
        if (parsed.prerollBytes) {
            apac::BitstreamReader preroll(packet + parsed.prerollOffset, parsed.prerollBytes);
            preroll.SkipBits(2);
            if (core.DecodeAPACFrame(preroll, 1, decoded) != apac::kOk || preroll.BitsRemaining() < 0) return false;
        }
        apac::BitstreamReader bits(packet, bytes);
        bits.SkipBits(parsed.audioBitOffset);
        if (core.DecodeAPACFrame(bits, parsed.type, decoded) != apac::kOk || bits.BitsRemaining() < 0) return false;
        for (int ch = 0; ch < channels; ch++) if (!decoded[ch]) return false;
        pcm.resize(1024 * channels * 2);
        for (int frame = 0; frame < 1024; frame++) for (int ch = 0; ch < channels; ch++) {
            float value = decoded[ch][frame]; // core PCM uses S16 scale
            if (!std::isfinite(value)) return false;
            int sample = int(std::lrint(std::fmax(-32768.f, std::fmin(32767.f, value))));
            size_t out = (frame * channels + ch) * 2;
            pcm[out] = uint8_t(sample); pcm[out + 1] = uint8_t(sample >> 8);
        }
        return true;
    }
    void reset() { core.Reset(); }
};
