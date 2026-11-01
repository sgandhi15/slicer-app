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

void run(State* state, int width, int height, int quality, int scaleNumerator) {
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
    jpeg_create_compress(encoder);
    configureMemory(reinterpret_cast<j_common_ptr>(encoder), state);
    jpeg_stdio_dest(encoder, state->output);
    encoder->image_width = decoder->output_width;
    encoder->image_height = decoder->output_height;
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
    JSAMPARRAY row = (*decoder->mem->alloc_sarray)(
        reinterpret_cast<j_common_ptr>(decoder), JPOOL_IMAGE, decoder->output_width * 3, 1);
    while (decoder->output_scanline < decoder->output_height) {
        checkpoint(state);
        if (jpeg_read_scanlines(decoder, row, 1) != 1 || jpeg_write_scanlines(encoder, row, 1) != 1) {
            fail(state, TranscodeStatus::InvalidData);
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
                              int64_t inputLength, int scaleNumerator) {
    if (!input || !output) return {TranscodeStatus::IoFailure, 0};
    if (width <= 0 || height <= 0 || width > JPEG_MAX_DIMENSION || height > JPEG_MAX_DIMENSION ||
        quality < 1 || quality > 100 || scaleNumerator < 1 || scaleNumerator > 8) {
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
    if (setjmp(state->jump) == 0) run(state, width, height, quality, scaleNumerator);
    if (state->decoder.mem) jpeg_destroy_decompress(&state->decoder);
    if (state->encoder.mem) jpeg_destroy_compress(&state->encoder);
    const TranscodeResult result{state->status,
        slicer_allocation_peak(state->allocations) + APPLICATION_WORKING_RESERVE, state->bytesRead};
    slicer_end_allocations(state->allocations);
    free(state);
    return result;
}
}
