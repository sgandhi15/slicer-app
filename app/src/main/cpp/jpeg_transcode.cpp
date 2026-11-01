#include "jpeg_transcode.h"
#include "codec_allocations.h"

#include <climits>
#include <algorithm>
#include <cstdlib>
#include <setjmp.h>
#include "jpeglib.h"
#include "jerror.h"

namespace slicer {
namespace {
struct State {
    jpeg_decompress_struct decoder;
    jpeg_compress_struct encoder;
    jpeg_error_mgr error;
    jpeg_progress_mgr progress;
    jpeg_source_mgr source;
    JOCTET inputBuffer[8192];
    jmp_buf jump;
    SlicerAllocationScope* allocations;
    FILE* input;
    FILE* output;
    bool (*cancelled)(void*);
    void* token;
    uint64_t budget;
    uint64_t bytesRead;
    int64_t remainingInput;
    TranscodeStatus status;
};

State* stateFor(j_common_ptr codec) {
    return static_cast<State*>(codec->client_data);
}

[[noreturn]] void fail(State* state, TranscodeStatus status) {
    state->status = status;
    longjmp(state->jump, 1);
}

void checkpoint(State* state) {
    if (state->cancelled && state->cancelled(state->token)) {
        fail(state, TranscodeStatus::Cancelled);
    }
}

void progress(j_common_ptr codec) { checkpoint(stateFor(codec)); }

void sourceInit(j_decompress_ptr) {}
void sourceTerm(j_decompress_ptr) {}

boolean readInput(j_decompress_ptr decoder) {
    auto* state = stateFor(reinterpret_cast<j_common_ptr>(decoder));
    checkpoint(state);
    const size_t requested = state->remainingInput < 0 ? sizeof(state->inputBuffer) :
        static_cast<size_t>(std::min<int64_t>(state->remainingInput, sizeof(state->inputBuffer)));
    const size_t count = fread(state->inputBuffer, 1, requested, state->input);
    state->bytesRead += count;
    // stdio may return some bytes AND set its error flag in the same read.
    // Even if those bytes contain EOI, an I/O failure must not become success.
    if (ferror(state->input)) fail(state, TranscodeStatus::IoFailure);
    if (count == 0) {
        fail(state, TranscodeStatus::InvalidData);
    }
    if (state->remainingInput >= 0) state->remainingInput -= count;
    state->source.next_input_byte = state->inputBuffer;
    state->source.bytes_in_buffer = count;
    return TRUE;
}

void skipInput(j_decompress_ptr decoder, long count) {
    if (count <= 0) return;
    while (static_cast<uint64_t>(count) > decoder->src->bytes_in_buffer) {
        count -= static_cast<long>(decoder->src->bytes_in_buffer);
        readInput(decoder);
    }
    decoder->src->next_input_byte += count;
    decoder->src->bytes_in_buffer -= count;
}

void errorExit(j_common_ptr codec) {
    auto* state = stateFor(codec);
    const int code = codec->err->msg_code;
    if (code == JERR_OUT_OF_MEMORY || code == JERR_NO_BACKING_STORE) {
        fail(state, TranscodeStatus::ResourceLimit);
    }
    if (ferror(state->input) || ferror(state->output) ||
        code == JERR_FILE_READ || code == JERR_FILE_WRITE) {
        fail(state, TranscodeStatus::IoFailure);
    }
    fail(state, TranscodeStatus::InvalidData);
}

void emitMessage(j_common_ptr codec, int level) {
    // libjpeg can otherwise repair truncated/corrupt input and report success.
    if (level < 0) errorExit(codec);
}

void allocationFailure(void* token) {
    fail(static_cast<State*>(token), TranscodeStatus::ResourceLimit);
}

constexpr uint64_t APPLICATION_WORKING_RESERVE = 1024 * 1024;

void configureMemory(j_common_ptr codec, State* state) {
    // Both codecs, scan buffers, and raw trellis allocations are charged to one
    // thread-local allocation scope. The codec must not try disk backing.
    codec->mem->max_memory_to_use = LONG_MAX;
    codec->progress = &state->progress;
}

// Bilinear sample positions along one axis, in 8-bit fixed point.
struct Taps {
    int* first;
    int* second;
    int* weight;
};

Taps makeTaps(j_common_ptr codec, JDIMENSION source, JDIMENSION target) {
    auto alloc = [&](size_t count) {
        return static_cast<int*>((*codec->mem->alloc_small)(codec, JPOOL_IMAGE, count * sizeof(int)));
    };
    Taps taps{alloc(target), alloc(target), alloc(target)};
    const double scale = static_cast<double>(source) / target;
    for (JDIMENSION i = 0; i < target; i++) {
        const double position = std::max(0.0, (i + 0.5) * scale - 0.5);
        const int first = std::min(static_cast<int>(position), static_cast<int>(source) - 1);
        taps.first[i] = first;
        taps.second[i] = std::min(first + 1, static_cast<int>(source) - 1);
        taps.weight[i] = static_cast<int>((position - first) * 256.0 + 0.5);
    }
    return taps;
}

void readRow(State* state, JSAMPARRAY row) {
    checkpoint(state);
    if (jpeg_read_scanlines(&state->decoder, row, 1) != 1) fail(state, TranscodeStatus::InvalidData);
}

// Streams the decoded rows through a bilinear resample, holding only two decoded rows at a time.
void writeResampled(State* state, JDIMENSION targetWidth, JDIMENSION targetHeight) {
    auto* decoder = &state->decoder;
    auto* codec = reinterpret_cast<j_common_ptr>(decoder);
    const JDIMENSION sourceWidth = decoder->output_width;
    JSAMPARRAY lines = (*decoder->mem->alloc_sarray)(codec, JPOOL_IMAGE, sourceWidth * 3, 2);
    JSAMPARRAY out = (*decoder->mem->alloc_sarray)(codec, JPOOL_IMAGE, targetWidth * 3, 1);
    const Taps columns = makeTaps(codec, sourceWidth, targetWidth);
    const Taps rows = makeTaps(codec, decoder->output_height, targetHeight);
    JSAMPROW previous = lines[0];
    JSAMPROW current = lines[1];
    int currentIndex = -1;
    for (JDIMENSION y = 0; y < targetHeight; y++) {
        while (currentIndex < rows.second[y]) {
            std::swap(previous, current);
            readRow(state, &current);
            currentIndex++;
        }
        const JSAMPROW top = rows.first[y] == currentIndex ? current : previous;
        const int wy = rows.weight[y];
        for (JDIMENSION x = 0; x < targetWidth; x++) {
            const int a = columns.first[x] * 3;
            const int b = columns.second[x] * 3;
            const int wx = columns.weight[x];
            for (int c = 0; c < 3; c++) {
                const int upper = top[a + c] * (256 - wx) + top[b + c] * wx;
                const int lower = current[a + c] * (256 - wx) + current[b + c] * wx;
                out[0][x * 3 + c] = static_cast<JSAMPLE>((upper * (256 - wy) + lower * wy + 32768) >> 16);
            }
        }
        if (jpeg_write_scanlines(&state->encoder, out, 1) != 1) fail(state, TranscodeStatus::InvalidData);
    }
    while (decoder->output_scanline < decoder->output_height) readRow(state, &current);
}

void run(State* state, int width, int height, int quality, int scaleNumerator,
         int outputWidth, int outputHeight) {
    auto* decoder = &state->decoder;
    auto* encoder = &state->encoder;
    checkpoint(state);
    if (state->budget <= APPLICATION_WORKING_RESERVE) fail(state, TranscodeStatus::ResourceLimit);
    state->allocations = slicer_begin_allocations(
        state->budget - APPLICATION_WORKING_RESERVE, allocationFailure, state);
    if (!state->allocations) fail(state, TranscodeStatus::ResourceLimit);
    jpeg_create_decompress(decoder);
    configureMemory(reinterpret_cast<j_common_ptr>(decoder), state);
    state->source.init_source = sourceInit;
    state->source.fill_input_buffer = readInput;
    state->source.skip_input_data = skipInput;
    state->source.resync_to_restart = jpeg_resync_to_restart;
    state->source.term_source = sourceTerm;
    decoder->src = &state->source;
    if (jpeg_read_header(decoder, TRUE) != JPEG_HEADER_OK ||
        decoder->image_width != static_cast<JDIMENSION>(width) ||
        decoder->image_height != static_cast<JDIMENSION>(height)) {
        fail(state, TranscodeStatus::InvalidData);
    }
    // Preserve RGB sample values and their ICC interpretation. CMYK/YCCK require
    // a separate color-management/metadata contract; never guess a conversion.
    if (decoder->data_precision != 8 ||
        (decoder->jpeg_color_space != JCS_RGB && decoder->jpeg_color_space != JCS_YCbCr &&
         decoder->jpeg_color_space != JCS_GRAYSCALE)) {
        fail(state, TranscodeStatus::Unsupported);
    }
    decoder->out_color_space = JCS_RGB;
    decoder->dct_method = JDCT_ISLOW;
    decoder->scale_num = static_cast<unsigned int>(scaleNumerator);
    decoder->scale_denom = 8;
    jpeg_calc_output_dimensions(decoder);
    const JDIMENSION targetWidth = outputWidth > 0 ? outputWidth : decoder->output_width;
    const JDIMENSION targetHeight = outputHeight > 0 ? outputHeight : decoder->output_height;
    if (targetWidth > decoder->output_width || targetHeight > decoder->output_height) {
        fail(state, TranscodeStatus::Unsupported);
    }
    jpeg_create_compress(encoder);
    configureMemory(reinterpret_cast<j_common_ptr>(encoder), state);
    jpeg_stdio_dest(encoder, state->output);
    encoder->image_width = targetWidth;
    encoder->image_height = targetHeight;
    encoder->input_components = 3;
    encoder->in_color_space = JCS_RGB;
    jpeg_c_set_int_param(encoder, JINT_COMPRESS_PROFILE, JCP_MAX_COMPRESSION);
    jpeg_set_defaults(encoder);
    jpeg_set_quality(encoder, quality, TRUE);
    encoder->dct_method = JDCT_ISLOW;
    encoder->optimize_coding = TRUE;
    encoder->comp_info[0].h_samp_factor = 2;
    encoder->comp_info[0].v_samp_factor = 2;
    encoder->comp_info[1].h_samp_factor = encoder->comp_info[1].v_samp_factor = 1;
    encoder->comp_info[2].h_samp_factor = encoder->comp_info[2].v_samp_factor = 1;
    // Searching scan layouts roughly doubles encode time for under 1% smaller files.
    jpeg_c_set_bool_param(encoder, JBOOLEAN_OPTIMIZE_SCANS, FALSE);
    jpeg_c_set_bool_param(encoder, JBOOLEAN_TRELLIS_QUANT, TRUE);
    jpeg_c_set_bool_param(encoder, JBOOLEAN_TRELLIS_QUANT_DC, TRUE);
    jpeg_simple_progression(encoder);
    jpeg_start_compress(encoder, TRUE);
    if (!jpeg_start_decompress(decoder)) fail(state, TranscodeStatus::InvalidData);
    if (targetWidth != decoder->output_width || targetHeight != decoder->output_height) {
        writeResampled(state, targetWidth, targetHeight);
    } else {
        JSAMPARRAY row = (*decoder->mem->alloc_sarray)(
            reinterpret_cast<j_common_ptr>(decoder), JPOOL_IMAGE, decoder->output_width * 3, 1);
        while (decoder->output_scanline < decoder->output_height) {
            readRow(state, row);
            if (jpeg_write_scanlines(encoder, row, 1) != 1) fail(state, TranscodeStatus::InvalidData);
        }
    }
    if (!jpeg_finish_decompress(decoder)) fail(state, TranscodeStatus::InvalidData);
    jpeg_finish_compress(encoder);
    checkpoint(state);
    if (fflush(state->output) != 0) fail(state, TranscodeStatus::IoFailure);
    state->status = TranscodeStatus::Success;
}
}

TranscodeResult transcodeJpeg(FILE* input, FILE* output, int width, int height, int quality,
                              uint64_t budgetBytes, bool (*cancelled)(void*), void* token,
                              int64_t inputLength, int scaleNumerator, int outputWidth, int outputHeight) {
    if (!input || !output) return {TranscodeStatus::IoFailure, 0};
    if (width <= 0 || height <= 0 || width > JPEG_MAX_DIMENSION || height > JPEG_MAX_DIMENSION ||
        quality < 1 || quality > 100 || scaleNumerator < 1 || scaleNumerator > 8 ||
        outputWidth < 0 || outputHeight < 0 || (outputWidth == 0) != (outputHeight == 0)) {
        return {TranscodeStatus::Unsupported, 0};
    }
    // Heap storage remains defined after longjmp; no C++ destructors are skipped.
    auto* state = static_cast<State*>(calloc(1, sizeof(State)));
    if (!state) return {TranscodeStatus::ResourceLimit, 0};
    state->input = input;
    state->output = output;
    state->budget = budgetBytes;
    state->remainingInput = inputLength;
    state->cancelled = cancelled;
    state->token = token;
    state->status = TranscodeStatus::InvalidData;
    state->decoder.client_data = state;
    state->encoder.client_data = state;
    state->decoder.err = state->encoder.err = jpeg_std_error(&state->error);
    state->error.error_exit = errorExit;
    state->error.emit_message = emitMessage;
    state->progress.progress_monitor = progress;
    if (setjmp(state->jump) == 0) run(state, width, height, quality, scaleNumerator, outputWidth, outputHeight);
    if (state->decoder.mem) jpeg_destroy_decompress(&state->decoder);
    if (state->encoder.mem) jpeg_destroy_compress(&state->encoder);
    const TranscodeResult result{state->status,
        slicer_allocation_peak(state->allocations) + APPLICATION_WORKING_RESERVE, state->bytesRead};
    slicer_end_allocations(state->allocations);
    free(state);
    return result;
}
}
