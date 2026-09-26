#include "jpeg_transcode.h"
#include "jpeglib.h"
#include <cassert>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {
constexpr int WIDTH = 193, HEIGHT = 127;
constexpr uint64_t BUDGET = 64 * 1024 * 1024;
struct Probes { int calls = 0; int stop = 0; };
bool cancelled(void* opaque) {
    auto* probes = static_cast<Probes*>(opaque);
    return ++probes->calls == probes->stop;
}

std::vector<unsigned char> fixture(bool progressive) {
    FILE* file = tmpfile();
    assert(file);
    jpeg_compress_struct codec{};
    jpeg_error_mgr error{};
    codec.err = jpeg_std_error(&error);
    jpeg_create_compress(&codec);
    jpeg_stdio_dest(&codec, file);
    codec.image_width = WIDTH;
    codec.image_height = HEIGHT;
    codec.input_components = 3;
    codec.in_color_space = JCS_RGB;
    jpeg_c_set_int_param(&codec, JINT_COMPRESS_PROFILE, JCP_FASTEST);
    jpeg_set_defaults(&codec);
    jpeg_set_quality(&codec, 95, TRUE);
    if (progressive) jpeg_simple_progression(&codec);
    jpeg_start_compress(&codec, TRUE);
    unsigned char row[WIDTH * 3];
    uint32_t random = 42;
    while (codec.next_scanline < HEIGHT) {
        for (auto& pixel : row) { random = random * 1664525 + 1013904223; pixel = random >> 24; }
        JSAMPROW rows[]{row};
        assert(jpeg_write_scanlines(&codec, rows, 1) == 1);
    }
    jpeg_finish_compress(&codec);
    jpeg_destroy_compress(&codec);
    const long size = ftell(file);
    assert(size > 0);
    rewind(file);
    std::vector<unsigned char> bytes(size);
    assert(fread(bytes.data(), 1, bytes.size(), file) == bytes.size());
    fclose(file);
    return bytes;
}

void validate(FILE* output) {
    rewind(output);
    jpeg_decompress_struct codec{};
    jpeg_error_mgr error{};
    codec.err = jpeg_std_error(&error);
    jpeg_create_decompress(&codec);
    jpeg_stdio_src(&codec, output);
    assert(jpeg_read_header(&codec, TRUE) == JPEG_HEADER_OK);
    assert(codec.image_width == WIDTH && codec.image_height == HEIGHT && codec.progressive_mode);
    assert(jpeg_start_decompress(&codec));
    std::vector<unsigned char> row(codec.output_width * codec.output_components);
    while (codec.output_scanline < codec.output_height) {
        JSAMPROW rows[]{row.data()};
        assert(jpeg_read_scanlines(&codec, rows, 1) == 1);
    }
    assert(jpeg_finish_decompress(&codec));
    jpeg_destroy_decompress(&codec);
}

slicer::TranscodeResult run(const std::vector<unsigned char>& bytes, uint64_t budget, Probes* probes) {
    FILE* input = tmpfile();
    FILE* output = tmpfile();
    assert(input && output);
    assert(fwrite(bytes.data(), 1, bytes.size(), input) == bytes.size());
    rewind(input);
    const auto result = slicer::transcodeJpeg(input, output, WIDTH, HEIGHT, 85, budget, cancelled, probes);
    if (result.status == slicer::TranscodeStatus::Success) validate(output);
    fclose(input);
    fclose(output);
    return result;
}

struct FailingRead { const std::vector<unsigned char>* bytes; size_t position = 0; };
ssize_t readThenFail(void* opaque, char* buffer, size_t size) {
    auto* cookie = static_cast<FailingRead*>(opaque);
    const size_t remaining = cookie->bytes->size() - cookie->position;
    if (!remaining) { errno = EIO; return -1; }
    const size_t count = remaining < size ? remaining : size;
    memcpy(buffer, cookie->bytes->data() + cookie->position, count);
    cookie->position += count;
    return static_cast<ssize_t>(count);
}

void checkIoFailures(const std::vector<unsigned char>& bytes) {
    FailingRead cookie{&bytes};
    cookie_io_functions_t functions{};
    functions.read = readThenFail;
    FILE* input = fopencookie(&cookie, "rb", functions);
    FILE* output = tmpfile();
    assert(input && output);
    auto result = slicer::transcodeJpeg(input, output, WIDTH, HEIGHT, 85, BUDGET, nullptr, nullptr);
    assert(result.status == slicer::TranscodeStatus::IoFailure);
    fclose(input);
    fclose(output);

    input = tmpfile();
    output = fopen("/dev/full", "wb");
    assert(input && output);
    assert(fwrite(bytes.data(), 1, bytes.size(), input) == bytes.size());
    rewind(input);
    result = slicer::transcodeJpeg(input, output, WIDTH, HEIGHT, 85, BUDGET, nullptr, nullptr);
    assert(result.status == slicer::TranscodeStatus::IoFailure);
    fclose(input);
    fclose(output);
}
}

int main() {
    for (bool progressive : {false, true}) {
        const auto bytes = fixture(progressive);
        Probes probes;
        const auto success = run(bytes, BUDGET, &probes);
        assert(success.status == slicer::TranscodeStatus::Success);
        const int completedCalls = probes.calls;
        for (uint64_t budget = 1024 * 1024; budget < success.estimatedPeakBytes; budget += 4096) {
            probes = {};
            assert(run(bytes, budget, &probes).status == slicer::TranscodeStatus::ResourceLimit);
        }
        for (int stop = 1; stop <= completedCalls; stop += 7) {
            probes = {0, stop};
            assert(run(bytes, BUDGET, &probes).status == slicer::TranscodeStatus::Cancelled);
        }
        for (size_t size = 0; size < bytes.size(); size += 193) {
            probes = {};
            assert(run({bytes.begin(), bytes.begin() + size}, BUDGET, &probes).status !=
                   slicer::TranscodeStatus::Success);
        }
        for (size_t index = 0; index < bytes.size(); index += 251) {
            auto damaged = bytes;
            damaged[index] ^= 0xFF;
            probes = {};
            run(damaged, BUDGET, &probes);
        }
        checkIoFailures(bytes);
    }
    puts("Native codec audit passed: budget, cancellation, corruption, truncation, and I/O sweeps.");
}
