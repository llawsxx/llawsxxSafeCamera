#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "mpegts_muxer.h"

#define PACKET_SIZE 188

static uint32_t crc32_mpeg(const uint8_t *data, size_t size) {
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < size; ++i) {
        crc ^= (uint32_t)data[i] << 24;
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc << 1) ^ ((crc & 0x80000000U) ? 0x04c11db7U : 0);
    }
    return crc;
}

static int pid(const uint8_t *packet) {
    return ((packet[1] & 0x1f) << 8) | packet[2];
}

static int payload_offset(const uint8_t *packet) {
    int offset = 4;
    if (packet[3] & 0x20)
        offset += packet[4] + 1;
    return offset;
}

static void check_section(const uint8_t *packet, int expected_table_id) {
    assert(packet[1] & 0x40);
    int offset = payload_offset(packet);
    assert(packet[offset++] == 0);
    const uint8_t *section = packet + offset;
    assert(section[0] == expected_table_id);
    size_t length = 3 + (((size_t)section[1] & 0x0f) << 8) + section[2];
    assert(crc32_mpeg(section, length) == 0);
}

int main(void) {
    TsMuxer *muxer = ts_muxer_create(TS_VIDEO_H264, 1, 48000, 2, 2);
    assert(muxer);
    const uint8_t csd0[] = {0, 0, 0, 1, 0x67, 0x64, 0, 0x1f};
    const uint8_t csd1[] = {0, 0, 0, 1, 0x68, 0xee, 0x3c, 0x80};
    assert(ts_muxer_set_video_config(muxer, csd0, sizeof(csd0),
                                     csd1, sizeof(csd1), NULL, 0) == 0);

    const uint8_t idr[] = {0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21};
    const uint8_t *output = NULL;
    size_t size = 0;
    assert(ts_muxer_write_video(muxer, idr, sizeof(idr), 1000000, 1,
                                &output, &size) == 0);
    assert(size >= 3 * PACKET_SIZE && size % PACKET_SIZE == 0);
    for (size_t offset = 0; offset < size; offset += PACKET_SIZE)
        assert(output[offset] == 0x47);
    assert(pid(output) == 0x0000);
    assert(pid(output + PACKET_SIZE) == 0x1000);
    check_section(output, 0x00);
    check_section(output + PACKET_SIZE, 0x02);

    const uint8_t *pmt = output + PACKET_SIZE;
    int pmt_payload = payload_offset(pmt) + 1;
    const uint8_t *pmt_section = pmt + pmt_payload;
    assert(pmt_section[12] == 0x1b);
    assert((((pmt_section[13] & 0x1f) << 8) | pmt_section[14]) == 0x0100);
    assert(pmt_section[17] == 0x0f);
    assert((((pmt_section[18] & 0x1f) << 8) | pmt_section[19]) == 0x0101);

    const uint8_t *video = output + 2 * PACKET_SIZE;
    assert(pid(video) == 0x0100);
    assert(video[1] & 0x40);
    assert(video[3] & 0x20);
    assert(video[5] & 0x40);
    assert(video[5] & 0x10);
    int video_payload = payload_offset(video);
    assert(memcmp(video + video_payload, "\x00\x00\x01\xe0", 4) == 0);

    const uint8_t aac[] = {0x21, 0x10, 0x56, 0xe5};
    assert(ts_muxer_write_audio(muxer, aac, sizeof(aac), 1000000,
                                &output, &size) == 0);
    assert(size % PACKET_SIZE == 0);
    const uint8_t *audio = output;
    assert(pid(audio) == 0x0101);
    int audio_payload = payload_offset(audio);
    assert(memcmp(audio + audio_payload, "\x00\x00\x01\xc0", 4) == 0);
    assert(audio[audio_payload + 14] == 0xff);
    assert((audio[audio_payload + 15] & 0xf6) == 0xf0);

    const uint8_t pframe[] = {0, 0, 0, 1, 0x41, 0x9a, 0x22};
    assert(ts_muxer_write_video(muxer, pframe, sizeof(pframe), 1033333, 0,
                                &output, &size) == 0);
    const uint8_t *first_video = output;
    while (pid(first_video) != 0x0100)
        first_video += PACKET_SIZE;
    assert((first_video[3] & 0x0f) == 1);

    ts_muxer_destroy(muxer);

    TsMuxer *audio_only = ts_muxer_create(TS_VIDEO_NONE, 1, 48000, 2, 2);
    assert(audio_only);
    assert(ts_muxer_write_audio(audio_only, aac, sizeof(aac), 0,
                                &output, &size) == 0);
    assert(size >= 3 * PACKET_SIZE && size % PACKET_SIZE == 0);
    assert(pid(output) == 0x0000);
    assert(pid(output + PACKET_SIZE) == 0x1000);
    const uint8_t *audio_pmt = output + PACKET_SIZE;
    const uint8_t *audio_section = audio_pmt + payload_offset(audio_pmt) + 1;
    assert(audio_section[12] == 0x0f);
    assert((((audio_section[13] & 0x1f) << 8) | audio_section[14]) == 0x0101);
    const uint8_t *audio_pes = output + 2 * PACKET_SIZE;
    assert(pid(audio_pes) == 0x0101);
    assert(audio_pes[5] & 0x10);
    ts_muxer_destroy(audio_only);

    for (int iteration = 0; iteration < 1000; ++iteration) {
        TsMuxer *stress = ts_muxer_create(TS_VIDEO_H264, 1, 48000, 2, 2);
        assert(stress);
        assert(ts_muxer_set_video_config(stress, csd0, sizeof(csd0),
                                         csd1, sizeof(csd1), NULL, 0) == 0);
        for (int frame = 0; frame < 100; ++frame) {
            assert(ts_muxer_write_video(stress, frame == 0 ? idr : pframe,
                                        frame == 0 ? sizeof(idr) : sizeof(pframe),
                                        frame * 33333LL, frame == 0,
                                        &output, &size) == 0);
            assert(ts_muxer_write_audio(stress, aac, sizeof(aac),
                                        frame * 21333LL, &output, &size) == 0);
        }
        ts_muxer_destroy(stress);
    }

    puts("mpegts_muxer_test: OK");
    return 0;
}
