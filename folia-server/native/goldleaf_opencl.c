/*
 * Zero-build-dependency C11 JNI + dlopen("libOpenCL.so.1") OpenCL 1.2+ bridge for GoldLeaf
 * DensityVolume batch evaluation (goldleaf-ADR-004).
 *
 * Design invariants:
 *   - No compile-time OpenCL headers required (uses minimal standard OpenCL 1.2 ABI typedefs).
 *   - Blacklists PoCL CPU drivers (CL_DEVICE_TYPE_GPU only + vendor/name PoCL filter).
 *   - Supports CL_PROGRAM_BINARIES export and clCreateProgramWithBinary warm reload for
 *     persistent on-disk kernel caching across server boots.
 *   - Strict lock hierarchy (S-002):
 *       Level 1: g_compile_mutex (init / compile / export / release)
 *       Level 2: g_devices[d].slots[0..1].slot_mutex, then g_devices[d].compute_mutex
 *       Level 3: handle->kernel_arg_mutex (microsecond clSetKernelArg + clEnqueueNDRangeKernel only)
 */

#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef uint64_t cl_bitfield;
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef void* cl_context;
typedef void* cl_command_queue;
typedef void* cl_program;
typedef void* cl_kernel;
typedef void* cl_mem;
typedef void* cl_event;

#define CL_SUCCESS 0
#define CL_DEVICE_TYPE_GPU (1ULL << 2)
#define CL_DEVICE_MAX_WORK_GROUP_SIZE 0x1004
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_VENDOR 0x102C
#define CL_DRIVER_VERSION 0x102D
#define CL_DEVICE_EXTENSIONS 0x1030
#define CL_MEM_READ_WRITE (1ULL << 0)
#define CL_MEM_WRITE_ONLY (1ULL << 1)
#define CL_MEM_READ_ONLY (1ULL << 2)
#define CL_MEM_ALLOC_HOST_PTR (1ULL << 4)
#define CL_MAP_READ (1ULL << 0)
#define CL_MAP_WRITE (1ULL << 1)
#define CL_PROGRAM_BINARY_SIZES 0x1165
#define CL_PROGRAM_BINARIES 0x1166
#define CL_PROGRAM_BUILD_LOG 0x1183
#define CL_QUEUE_PROFILING_ENABLE (1ULL << 1)
#define CL_PROFILING_COMMAND_START 0x1282
#define CL_PROFILING_COMMAND_END 0x1283

#define MAX_OPENCL_PLATFORMS 8
#define MAX_OPENCL_DEVICES 8
#define GOLDLEAF_DMA_SLOTS 2
#define GOLDLEAF_ORE_SLOTS 4
#define GOLDLEAF_LIGHT_SLOTS 4

typedef cl_int (*pfn_clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*);
typedef cl_int (*pfn_clGetDeviceIDs)(cl_platform_id, cl_bitfield, cl_uint, cl_device_id*, cl_uint*);
typedef cl_int (*pfn_clGetDeviceInfo)(cl_device_id, cl_uint, size_t, void*, size_t*);
typedef cl_context (*pfn_clCreateContext)(const void*, cl_uint, const cl_device_id*, void*, void*, cl_int*);
typedef cl_command_queue (*pfn_clCreateCommandQueue)(cl_context, cl_device_id, cl_bitfield, cl_int*);
typedef cl_program (*pfn_clCreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*);
typedef cl_program (*pfn_clCreateProgramWithBinary)(cl_context, cl_uint, const cl_device_id*, const size_t*, const unsigned char**, cl_int*, cl_int*);
typedef cl_int (*pfn_clBuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void*, void*);
typedef cl_int (*pfn_clGetProgramInfo)(cl_program, cl_uint, size_t, void*, size_t*);
typedef cl_int (*pfn_clGetProgramBuildInfo)(cl_program, cl_device_id, cl_uint, size_t, void*, size_t*);
typedef cl_kernel (*pfn_clCreateKernel)(cl_program, const char*, cl_int*);
typedef cl_mem (*pfn_clCreateBuffer)(cl_context, cl_bitfield, size_t, void*, cl_int*);
typedef cl_int (*pfn_clSetKernelArg)(cl_kernel, cl_uint, size_t, const void*);
typedef cl_int (*pfn_clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const cl_event*, cl_event*);
typedef cl_int (*pfn_clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_uint, size_t, size_t, void*, cl_uint, const cl_event*, cl_event*);
typedef cl_int (*pfn_clEnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_uint, size_t, size_t, const void*, cl_uint, const cl_event*, cl_event*);
typedef cl_int (*pfn_clEnqueueFillBuffer)(cl_command_queue, cl_mem, const void*, size_t, size_t, size_t, cl_uint, const cl_event*, cl_event*);
typedef void* (*pfn_clEnqueueMapBuffer)(cl_command_queue, cl_mem, cl_uint, cl_bitfield, size_t, size_t, cl_uint, const cl_event*, cl_event*, cl_int*);
typedef cl_int (*pfn_clEnqueueUnmapMemObject)(cl_command_queue, cl_mem, void*, cl_uint, const cl_event*, cl_event*);
typedef cl_int (*pfn_clFinish)(cl_command_queue);
typedef cl_int (*pfn_clWaitForEvents)(cl_uint, const cl_event*);
typedef cl_int (*pfn_clGetEventProfilingInfo)(cl_event, cl_uint, size_t, void*, size_t*);
typedef cl_int (*pfn_clReleaseEvent)(cl_event);
typedef cl_int (*pfn_clReleaseMemObject)(cl_mem);
typedef cl_int (*pfn_clReleaseKernel)(cl_kernel);
typedef cl_int (*pfn_clReleaseProgram)(cl_program);
typedef cl_int (*pfn_clReleaseCommandQueue)(cl_command_queue);

typedef struct {
    cl_command_queue queue;
    pthread_mutex_t slot_mutex;
    int mutex_initialized;
    cl_mem origins_buf;
    cl_mem origins_host_buf;
    int32_t* origins_host_ptr;
    size_t origins_cap_chunks;
    cl_mem out_values_buf;
    cl_mem out_host_buf;
    float* out_host_ptr;
    size_t out_values_cap_floats;
    cl_mem compact_words_buf;
    cl_mem compact_words_host_buf;
    uint32_t* compact_words_host_ptr;
    size_t compact_cap_word_bytes;
    cl_mem compact_meta_buf;
    cl_mem compact_meta_host_buf;
    uint16_t* compact_meta_host_ptr;
    size_t compact_cap_sections;
    cl_mem biomes_halo_buf;
    int8_t* biomes_halo_host;
    size_t biomes_halo_cap_bytes;
    cl_mem prelim_corners_buf;
    float* prelim_corners_host;
    size_t prelim_corners_cap_bytes;
    cl_mem chunk_flags_buf;
    size_t chunk_flags_cap_bytes;
    cl_mem heightmaps_buf;
    size_t heightmaps_cap_bytes;
    cl_mem beard_meta_buf;
    int32_t* beard_meta_host;
    size_t beard_meta_cap_bytes;
    cl_mem beard_items_buf;
    int32_t* beard_items_host;
    size_t beard_items_cap_bytes;
    cl_mem carver_meta_buf;
    int32_t* carver_meta_host;
    size_t carver_meta_cap_bytes;
    cl_mem carver_masks_buf;
    int32_t* carver_masks_host;
    size_t carver_masks_cap_bytes;
    cl_mem carver_events_buf;
    jint* carver_events_host;
    size_t carver_events_cap_bytes;
    cl_mem carver_counts_buf;
    size_t carver_counts_cap_bytes;
    cl_mem aquifer_cells_buf;
    size_t aquifer_cells_cap_bytes;
} GpuDmaSlot;

typedef struct {
    cl_command_queue queue;
    pthread_mutex_t slot_mutex;
    int mutex_initialized;
    cl_mem ore_meta_buf;
    size_t ore_meta_cap_bytes;
    int32_t* ore_meta_host;
    cl_mem ore_rel6_buf;
    size_t ore_rel6_cap_bytes;
    float* ore_rel6_host;
    cl_mem ore_seeds_buf;
    size_t ore_seeds_cap_bytes;
    uint64_t* ore_seeds_host;
    cl_mem ore_slices_buf;
    size_t ore_slices_cap_bytes;
    int32_t* ore_slices_host;
    cl_mem ore_voxels_buf;
    size_t ore_voxels_cap_bytes;
    uint32_t* ore_voxels_host;
    cl_mem ore_counter_buf;
    uint32_t ore_zero_val;
} OreDmaSlot;

typedef struct {
    cl_command_queue queue;
    pthread_mutex_t slot_mutex;
    int mutex_initialized;
    cl_mem meta_buf;
    size_t meta_cap_bytes;
    int32_t* meta_host;
    cl_mem voxel_info_buf;
    size_t voxel_info_cap_bytes;
    uint8_t* voxel_info_host;
    cl_mem light_grid_buf;
    size_t light_grid_cap_bytes;
    cl_mem sky_nibbles_buf;
    size_t sky_nibbles_cap_bytes;
    uint8_t* sky_nibbles_host;
    cl_mem blk_nibbles_buf;
    size_t blk_nibbles_cap_bytes;
    uint8_t* blk_nibbles_host;
    cl_mem sec_flags_buf;
    size_t sec_flags_cap_bytes;
    uint8_t* sec_flags_host;
} LightDmaSlot;

typedef struct {
    cl_platform_id platform;
    cl_device_id device;
    cl_context context;
    char name[192];
    char vendor[128];
    char driver_version[128];
    int supports_fp64;
    size_t max_work_group_size;
    size_t preferred_local_size;
    uint32_t next_slot;
    GpuDmaSlot slots[GOLDLEAF_DMA_SLOTS];
    uint32_t next_ore_slot;
    OreDmaSlot ore_slots[GOLDLEAF_ORE_SLOTS];
    uint32_t next_light_slot;
    LightDmaSlot light_slots[GOLDLEAF_LIGHT_SLOTS];
    cl_mem dummy_spill_buf;
    cl_command_queue compute_queue;
    pthread_mutex_t compute_mutex;
    int compute_mutex_initialized;
    cl_mem compute_in_buf;
    size_t compute_in_cap_bytes;
    cl_mem compute_out_buf;
    size_t compute_out_cap_bytes;
    cl_mem compute_params_buf;
    size_t compute_params_cap_bytes;
    cl_mem compute_dummy_rw_buf;
} GoldLeafDeviceState;

typedef struct {
    int device_index;
    cl_mem buf;
    size_t size_bytes;
} GoldLeafComputeVramBuffer;

typedef struct {
    int device_index;
    cl_program program;
    cl_kernel kernel;
    cl_kernel terrain_aquifer_kernel;
    cl_mem spill_buf;
    cl_mem block_dict_buf;
    pthread_mutex_t kernel_arg_mutex;
} GoldLeafProgramHandle;

#define GOLDLEAF_MAX_CARVER_EVENTS 2048
#define GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK (4 + 384 * 2)

static void* g_opencl_dl = NULL;
static int g_initialized = 0;
static int g_device_count = 0;
static GoldLeafDeviceState g_devices[MAX_OPENCL_DEVICES];
static pthread_mutex_t g_compile_mutex = PTHREAD_MUTEX_INITIALIZER;
static char g_last_build_log[8192];

static pfn_clGetPlatformIDs fn_clGetPlatformIDs = NULL;
static pfn_clGetDeviceIDs fn_clGetDeviceIDs = NULL;
static pfn_clGetDeviceInfo fn_clGetDeviceInfo = NULL;
static pfn_clCreateContext fn_clCreateContext = NULL;
static pfn_clCreateCommandQueue fn_clCreateCommandQueue = NULL;
static pfn_clCreateProgramWithSource fn_clCreateProgramWithSource = NULL;
static pfn_clCreateProgramWithBinary fn_clCreateProgramWithBinary = NULL;
static pfn_clBuildProgram fn_clBuildProgram = NULL;
static pfn_clGetProgramInfo fn_clGetProgramInfo = NULL;
static pfn_clGetProgramBuildInfo fn_clGetProgramBuildInfo = NULL;
static pfn_clCreateKernel fn_clCreateKernel = NULL;
static pfn_clCreateBuffer fn_clCreateBuffer = NULL;
static pfn_clSetKernelArg fn_clSetKernelArg = NULL;
static pfn_clEnqueueNDRangeKernel fn_clEnqueueNDRangeKernel = NULL;
static pfn_clEnqueueReadBuffer fn_clEnqueueReadBuffer = NULL;
static pfn_clEnqueueWriteBuffer fn_clEnqueueWriteBuffer = NULL;
static pfn_clEnqueueFillBuffer fn_clEnqueueFillBuffer = NULL;
static pfn_clEnqueueMapBuffer fn_clEnqueueMapBuffer = NULL;
static pfn_clEnqueueUnmapMemObject fn_clEnqueueUnmapMemObject = NULL;
static pfn_clFinish fn_clFinish = NULL;
static pfn_clWaitForEvents fn_clWaitForEvents = NULL;
static pfn_clGetEventProfilingInfo fn_clGetEventProfilingInfo = NULL;
static pfn_clReleaseEvent fn_clReleaseEvent = NULL;
static pfn_clReleaseMemObject fn_clReleaseMemObject = NULL;
static pfn_clReleaseKernel fn_clReleaseKernel = NULL;
static pfn_clReleaseProgram fn_clReleaseProgram = NULL;
static pfn_clReleaseCommandQueue fn_clReleaseCommandQueue = NULL;

static void release_slot_compact_buffers_locked(GpuDmaSlot* slot) {
    if (slot->compact_words_host_buf) {
        if (slot->compact_words_host_ptr && slot->queue) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->compact_words_host_buf, slot->compact_words_host_ptr, 0, NULL, NULL);
            slot->compact_words_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->compact_words_host_buf);
        slot->compact_words_host_buf = NULL;
    }
    if (slot->compact_meta_host_buf) {
        if (slot->compact_meta_host_ptr && slot->queue) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->compact_meta_host_buf, slot->compact_meta_host_ptr, 0, NULL, NULL);
            slot->compact_meta_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->compact_meta_host_buf);
        slot->compact_meta_host_buf = NULL;
    }
    if (slot->compact_words_buf) {
        fn_clReleaseMemObject(slot->compact_words_buf);
        slot->compact_words_buf = NULL;
    }
    if (slot->compact_meta_buf) {
        fn_clReleaseMemObject(slot->compact_meta_buf);
        slot->compact_meta_buf = NULL;
    }
    if (slot->queue) {
        fn_clFinish(slot->queue);
    }
    slot->compact_cap_sections = 0;
    slot->compact_cap_word_bytes = 0;
}

static void release_dma_slot_locked(GpuDmaSlot* slot) {
    if (slot->origins_host_buf) {
        if (slot->origins_host_ptr && slot->queue) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->origins_host_buf, slot->origins_host_ptr, 0, NULL, NULL);
            slot->origins_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->origins_host_buf);
        slot->origins_host_buf = NULL;
    }
    if (slot->origins_buf) {
        fn_clReleaseMemObject(slot->origins_buf);
        slot->origins_buf = NULL;
    }
    slot->origins_cap_chunks = 0;

    if (slot->out_host_buf) {
        if (slot->out_host_ptr && slot->queue) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->out_host_buf, slot->out_host_ptr, 0, NULL, NULL);
            slot->out_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->out_host_buf);
        slot->out_host_buf = NULL;
    }
    if (slot->out_values_buf) {
        fn_clReleaseMemObject(slot->out_values_buf);
        slot->out_values_buf = NULL;
    }
    slot->out_values_cap_floats = 0;

    release_slot_compact_buffers_locked(slot);

    if (slot->biomes_halo_buf) {
        fn_clReleaseMemObject(slot->biomes_halo_buf);
        slot->biomes_halo_buf = NULL;
    }
    free(slot->biomes_halo_host);
    slot->biomes_halo_host = NULL;
    slot->biomes_halo_cap_bytes = 0;
    if (slot->prelim_corners_buf) {
        fn_clReleaseMemObject(slot->prelim_corners_buf);
        slot->prelim_corners_buf = NULL;
    }
    free(slot->prelim_corners_host);
    slot->prelim_corners_host = NULL;
    slot->prelim_corners_cap_bytes = 0;
    if (slot->chunk_flags_buf) {
        fn_clReleaseMemObject(slot->chunk_flags_buf);
        slot->chunk_flags_buf = NULL;
    }
    slot->chunk_flags_cap_bytes = 0;
    if (slot->heightmaps_buf) {
        fn_clReleaseMemObject(slot->heightmaps_buf);
        slot->heightmaps_buf = NULL;
    }
    slot->heightmaps_cap_bytes = 0;
    if (slot->beard_meta_buf) {
        fn_clReleaseMemObject(slot->beard_meta_buf);
        slot->beard_meta_buf = NULL;
    }
    free(slot->beard_meta_host);
    slot->beard_meta_host = NULL;
    slot->beard_meta_cap_bytes = 0;
    if (slot->beard_items_buf) {
        fn_clReleaseMemObject(slot->beard_items_buf);
        slot->beard_items_buf = NULL;
    }
    free(slot->beard_items_host);
    slot->beard_items_host = NULL;
    slot->beard_items_cap_bytes = 0;
    if (slot->carver_meta_buf) {
        fn_clReleaseMemObject(slot->carver_meta_buf);
        slot->carver_meta_buf = NULL;
    }
    free(slot->carver_meta_host);
    slot->carver_meta_host = NULL;
    slot->carver_meta_cap_bytes = 0;
    if (slot->carver_masks_buf) {
        fn_clReleaseMemObject(slot->carver_masks_buf);
        slot->carver_masks_buf = NULL;
    }
    free(slot->carver_masks_host);
    slot->carver_masks_host = NULL;
    slot->carver_masks_cap_bytes = 0;
    if (slot->carver_events_buf) {
        fn_clReleaseMemObject(slot->carver_events_buf);
        slot->carver_events_buf = NULL;
    }
    free(slot->carver_events_host);
    slot->carver_events_host = NULL;
    slot->carver_events_cap_bytes = 0;
    if (slot->carver_counts_buf) {
        fn_clReleaseMemObject(slot->carver_counts_buf);
        slot->carver_counts_buf = NULL;
    }
    slot->carver_counts_cap_bytes = 0;
    if (slot->aquifer_cells_buf) {
        fn_clReleaseMemObject(slot->aquifer_cells_buf);
        slot->aquifer_cells_buf = NULL;
    }
    slot->aquifer_cells_cap_bytes = 0;

    if (slot->queue) {
        fn_clFinish(slot->queue);
        if (fn_clReleaseCommandQueue) {
            fn_clReleaseCommandQueue(slot->queue);
        }
        slot->queue = NULL;
    }
    if (slot->mutex_initialized) {
        pthread_mutex_destroy(&slot->slot_mutex);
        slot->mutex_initialized = 0;
    }
}

static void release_ore_slot_locked(OreDmaSlot* slot) {
    if (slot->ore_meta_buf) { fn_clReleaseMemObject(slot->ore_meta_buf); slot->ore_meta_buf = NULL; }
    free(slot->ore_meta_host); slot->ore_meta_host = NULL; slot->ore_meta_cap_bytes = 0;
    if (slot->ore_rel6_buf) { fn_clReleaseMemObject(slot->ore_rel6_buf); slot->ore_rel6_buf = NULL; }
    free(slot->ore_rel6_host); slot->ore_rel6_host = NULL; slot->ore_rel6_cap_bytes = 0;
    if (slot->ore_seeds_buf) { fn_clReleaseMemObject(slot->ore_seeds_buf); slot->ore_seeds_buf = NULL; }
    free(slot->ore_seeds_host); slot->ore_seeds_host = NULL; slot->ore_seeds_cap_bytes = 0;
    if (slot->ore_slices_buf) { fn_clReleaseMemObject(slot->ore_slices_buf); slot->ore_slices_buf = NULL; }
    free(slot->ore_slices_host); slot->ore_slices_host = NULL; slot->ore_slices_cap_bytes = 0;
    if (slot->ore_voxels_buf) { fn_clReleaseMemObject(slot->ore_voxels_buf); slot->ore_voxels_buf = NULL; }
    free(slot->ore_voxels_host); slot->ore_voxels_host = NULL; slot->ore_voxels_cap_bytes = 0;
    if (slot->ore_counter_buf) { fn_clReleaseMemObject(slot->ore_counter_buf); slot->ore_counter_buf = NULL; }
    if (slot->queue) {
        fn_clFinish(slot->queue);
        if (fn_clReleaseCommandQueue) {
            fn_clReleaseCommandQueue(slot->queue);
        }
        slot->queue = NULL;
    }
    if (slot->mutex_initialized) {
        pthread_mutex_destroy(&slot->slot_mutex);
        slot->mutex_initialized = 0;
    }
}

static void release_light_slot_locked(LightDmaSlot* slot) {
    if (slot->meta_buf) { fn_clReleaseMemObject(slot->meta_buf); slot->meta_buf = NULL; }
    free(slot->meta_host); slot->meta_host = NULL; slot->meta_cap_bytes = 0;
    if (slot->voxel_info_buf) { fn_clReleaseMemObject(slot->voxel_info_buf); slot->voxel_info_buf = NULL; }
    free(slot->voxel_info_host); slot->voxel_info_host = NULL; slot->voxel_info_cap_bytes = 0;
    if (slot->light_grid_buf) { fn_clReleaseMemObject(slot->light_grid_buf); slot->light_grid_buf = NULL; }
    slot->light_grid_cap_bytes = 0;
    if (slot->sky_nibbles_buf) { fn_clReleaseMemObject(slot->sky_nibbles_buf); slot->sky_nibbles_buf = NULL; }
    free(slot->sky_nibbles_host); slot->sky_nibbles_host = NULL; slot->sky_nibbles_cap_bytes = 0;
    if (slot->blk_nibbles_buf) { fn_clReleaseMemObject(slot->blk_nibbles_buf); slot->blk_nibbles_buf = NULL; }
    free(slot->blk_nibbles_host); slot->blk_nibbles_host = NULL; slot->blk_nibbles_cap_bytes = 0;
    if (slot->sec_flags_buf) { fn_clReleaseMemObject(slot->sec_flags_buf); slot->sec_flags_buf = NULL; }
    free(slot->sec_flags_host); slot->sec_flags_host = NULL; slot->sec_flags_cap_bytes = 0;
    if (slot->queue) {
        fn_clFinish(slot->queue);
        if (fn_clReleaseCommandQueue) {
            fn_clReleaseCommandQueue(slot->queue);
        }
        slot->queue = NULL;
    }
    if (slot->mutex_initialized) {
        pthread_mutex_destroy(&slot->slot_mutex);
        slot->mutex_initialized = 0;
    }
}

static OreDmaSlot* acquire_next_ore_slot(GoldLeafDeviceState* dev) {
    uint32_t idx = __sync_fetch_and_add(&dev->next_ore_slot, 1U) & (GOLDLEAF_ORE_SLOTS - 1U);
    return &dev->ore_slots[idx];
}

static GpuDmaSlot* lock_next_dma_slot(GoldLeafDeviceState* dev) {
    uint32_t start = __sync_fetch_and_add(&dev->next_slot, 1U) & (GOLDLEAF_DMA_SLOTS - 1U);
    for (int attempt = 0; attempt < GOLDLEAF_DMA_SLOTS; attempt++) {
        GpuDmaSlot* cand = &dev->slots[(start + (uint32_t)attempt) & (GOLDLEAF_DMA_SLOTS - 1U)];
        if (pthread_mutex_trylock(&cand->slot_mutex) == 0) {
            return cand;
        }
    }
    GpuDmaSlot* fallback = &dev->slots[start];
    pthread_mutex_lock(&fallback->slot_mutex);
    return fallback;
}

static cl_int ensure_slot_origins_locked(GoldLeafDeviceState* dev, GpuDmaSlot* slot, size_t batch_count) {
    if (slot->origins_cap_chunks >= batch_count) {
        return CL_SUCCESS;
    }
    if (slot->origins_host_buf) {
        if (slot->origins_host_ptr) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->origins_host_buf, slot->origins_host_ptr, 0, NULL, NULL);
            fn_clFinish(slot->queue);
            slot->origins_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->origins_host_buf);
        slot->origins_host_buf = NULL;
    }
    if (slot->origins_buf) {
        fn_clReleaseMemObject(slot->origins_buf);
        slot->origins_buf = NULL;
    }
    cl_int err = CL_SUCCESS;
    size_t new_cap = batch_count < 64 ? 64 : batch_count;
    size_t bytes = new_cap * 4 * sizeof(int32_t);
    slot->origins_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
    if (!slot->origins_buf || err != CL_SUCCESS) {
        slot->origins_cap_chunks = 0;
        return err != CL_SUCCESS ? err : -5;
    }
    cl_int map_err = CL_SUCCESS;
    slot->origins_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, NULL, &map_err);
    if (slot->origins_host_buf && map_err == CL_SUCCESS) {
        slot->origins_host_ptr = (int32_t*)fn_clEnqueueMapBuffer(
            slot->queue, slot->origins_host_buf, 1, CL_MAP_WRITE, 0, bytes, 0, NULL, NULL, &map_err);
        if (!slot->origins_host_ptr || map_err != CL_SUCCESS) {
            fn_clReleaseMemObject(slot->origins_host_buf);
            slot->origins_host_buf = NULL;
            slot->origins_host_ptr = NULL;
        }
    } else {
        slot->origins_host_buf = NULL;
        slot->origins_host_ptr = NULL;
    }
    slot->origins_cap_chunks = new_cap;
    return CL_SUCCESS;
}

static cl_int ensure_slot_out_values_locked(GoldLeafDeviceState* dev, GpuDmaSlot* slot, size_t total_floats) {
    if (slot->out_values_cap_floats >= total_floats) {
        return CL_SUCCESS;
    }
    if (slot->out_host_buf) {
        if (slot->out_host_ptr) {
            fn_clEnqueueUnmapMemObject(slot->queue, slot->out_host_buf, slot->out_host_ptr, 0, NULL, NULL);
            fn_clFinish(slot->queue);
            slot->out_host_ptr = NULL;
        }
        fn_clReleaseMemObject(slot->out_host_buf);
        slot->out_host_buf = NULL;
    }
    if (slot->out_values_buf) {
        fn_clReleaseMemObject(slot->out_values_buf);
        slot->out_values_buf = NULL;
    }
    cl_int err = CL_SUCCESS;
    size_t out_bytes = total_floats * sizeof(float);
    slot->out_values_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, out_bytes, NULL, &err);
    if (!slot->out_values_buf || err != CL_SUCCESS) {
        slot->out_values_cap_floats = 0;
        return err != CL_SUCCESS ? err : -6;
    }
    cl_int map_err = CL_SUCCESS;
    slot->out_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, out_bytes, NULL, &map_err);
    if (slot->out_host_buf && map_err == CL_SUCCESS) {
        slot->out_host_ptr = (float*)fn_clEnqueueMapBuffer(
            slot->queue, slot->out_host_buf, 1, CL_MAP_READ, 0, out_bytes, 0, NULL, NULL, &map_err);
        if (!slot->out_host_ptr || map_err != CL_SUCCESS) {
            fn_clReleaseMemObject(slot->out_host_buf);
            slot->out_host_buf = NULL;
            slot->out_host_ptr = NULL;
        }
    } else {
        slot->out_host_buf = NULL;
        slot->out_host_ptr = NULL;
    }
    slot->out_values_cap_floats = total_floats;
    return CL_SUCCESS;
}

static cl_int ensure_slot_compact_buffers_locked(
    GoldLeafDeviceState* dev,
    GpuDmaSlot* slot,
    size_t total_sections,
    size_t words_bytes,
    size_t word_elem_size
) {
    if (slot->compact_cap_sections >= total_sections && slot->compact_cap_word_bytes >= words_bytes) {
        return CL_SUCCESS;
    }
    release_slot_compact_buffers_locked(slot);
    size_t new_sec_cap = total_sections < 768 ? 768 : total_sections;
    size_t alloc_words_bytes = new_sec_cap * 256 * word_elem_size;
    if (alloc_words_bytes < words_bytes) {
        alloc_words_bytes = words_bytes;
    }
    size_t alloc_meta_bytes = new_sec_cap * 4 * sizeof(uint16_t);
    cl_int err = CL_SUCCESS;
    slot->compact_words_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_words_bytes, NULL, &err);
    if (!slot->compact_words_buf || err != CL_SUCCESS) {
        release_slot_compact_buffers_locked(slot);
        return err != CL_SUCCESS ? err : -6;
    }
    slot->compact_meta_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_meta_bytes, NULL, &err);
    if (!slot->compact_meta_buf || err != CL_SUCCESS) {
        release_slot_compact_buffers_locked(slot);
        return err != CL_SUCCESS ? err : -6;
    }
    cl_int map_err = CL_SUCCESS;
    slot->compact_words_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_words_bytes, NULL, &map_err);
    if (slot->compact_words_host_buf && map_err == CL_SUCCESS) {
        slot->compact_words_host_ptr = (uint32_t*)fn_clEnqueueMapBuffer(
            slot->queue, slot->compact_words_host_buf, 1, CL_MAP_READ, 0, alloc_words_bytes, 0, NULL, NULL, &map_err);
        if (!slot->compact_words_host_ptr || map_err != CL_SUCCESS) {
            fn_clReleaseMemObject(slot->compact_words_host_buf);
            slot->compact_words_host_buf = NULL;
            slot->compact_words_host_ptr = NULL;
        }
    }
    map_err = CL_SUCCESS;
    slot->compact_meta_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_meta_bytes, NULL, &map_err);
    if (slot->compact_meta_host_buf && map_err == CL_SUCCESS) {
        slot->compact_meta_host_ptr = (uint16_t*)fn_clEnqueueMapBuffer(
            slot->queue, slot->compact_meta_host_buf, 1, CL_MAP_READ, 0, alloc_meta_bytes, 0, NULL, NULL, &map_err);
        if (!slot->compact_meta_host_ptr || map_err != CL_SUCCESS) {
            fn_clReleaseMemObject(slot->compact_meta_host_buf);
            slot->compact_meta_host_buf = NULL;
            slot->compact_meta_host_ptr = NULL;
        }
    }
    slot->compact_cap_sections = new_sec_cap;
    slot->compact_cap_word_bytes = alloc_words_bytes;
    return CL_SUCCESS;
}

static uint64_t monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static int contains_icase(const char* haystack, const char* needle) {
    if (!haystack || !needle || !*needle) {
        return 0;
    }
    size_t nlen = strlen(needle);
    for (const char* p = haystack; *p; p++) {
        size_t i = 0;
        while (i < nlen && p[i] && tolower((unsigned char)p[i]) == tolower((unsigned char)needle[i])) {
            i++;
        }
        if (i == nlen) {
            return 1;
        }
    }
    return 0;
}

static int is_blacklisted_pocl_device(const char* vendor, const char* name) {
    return contains_icase(vendor, "pocl")
        || contains_icase(vendor, "portable computing language")
        || contains_icase(name, "pocl")
        || contains_icase(name, "portable computing language");
}

static void capture_build_log_locked(cl_program prog, cl_device_id dev) {
    g_last_build_log[0] = '\0';
    if (!fn_clGetProgramBuildInfo) {
        return;
    }
    size_t log_size = 0;
    if (fn_clGetProgramBuildInfo(prog, dev, CL_PROGRAM_BUILD_LOG, 0, NULL, &log_size) != CL_SUCCESS || log_size <= 1) {
        return;
    }
    char* buf = (char*)malloc(log_size + 1);
    if (!buf) {
        return;
    }
    if (fn_clGetProgramBuildInfo(prog, dev, CL_PROGRAM_BUILD_LOG, log_size, buf, NULL) == CL_SUCCESS) {
        buf[log_size] = '\0';
        size_t copy_len = strlen(buf);
        if (copy_len >= sizeof(g_last_build_log)) {
            copy_len = sizeof(g_last_build_log) - 1;
        }
        memcpy(g_last_build_log, buf, copy_len);
        g_last_build_log[copy_len] = '\0';
    }
    free(buf);
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeInit(
    JNIEnv* env,
    jclass cls
) {
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_compile_mutex);
    if (g_initialized) {
        int count = g_device_count;
        pthread_mutex_unlock(&g_compile_mutex);
        return (jint)count;
    }

    g_opencl_dl = dlopen("libOpenCL.so.1", RTLD_LAZY | RTLD_LOCAL);
    if (!g_opencl_dl) {
        g_opencl_dl = dlopen("libOpenCL.so", RTLD_LAZY | RTLD_LOCAL);
    }
    if (!g_opencl_dl) {
        g_initialized = 1;
        g_device_count = 0;
        pthread_mutex_unlock(&g_compile_mutex);
        return -1;
    }

#define LOAD_SYM(sym) do { \
    fn_##sym = (pfn_##sym)dlsym(g_opencl_dl, #sym); \
    if (!fn_##sym) { \
        dlclose(g_opencl_dl); \
        g_opencl_dl = NULL; \
        g_initialized = 1; \
        g_device_count = 0; \
        pthread_mutex_unlock(&g_compile_mutex); \
        return -2; \
    } \
} while (0)

    LOAD_SYM(clGetPlatformIDs);
    LOAD_SYM(clGetDeviceIDs);
    LOAD_SYM(clGetDeviceInfo);
    LOAD_SYM(clCreateContext);
    LOAD_SYM(clCreateCommandQueue);
    LOAD_SYM(clCreateProgramWithSource);
    LOAD_SYM(clCreateProgramWithBinary);
    LOAD_SYM(clBuildProgram);
    LOAD_SYM(clGetProgramInfo);
    LOAD_SYM(clGetProgramBuildInfo);
    LOAD_SYM(clCreateKernel);
    LOAD_SYM(clCreateBuffer);
    LOAD_SYM(clSetKernelArg);
    LOAD_SYM(clEnqueueNDRangeKernel);
    LOAD_SYM(clEnqueueReadBuffer);
    LOAD_SYM(clEnqueueWriteBuffer);
    LOAD_SYM(clEnqueueFillBuffer);
    LOAD_SYM(clEnqueueMapBuffer);
    LOAD_SYM(clEnqueueUnmapMemObject);
    LOAD_SYM(clFinish);
    LOAD_SYM(clWaitForEvents);
    LOAD_SYM(clGetEventProfilingInfo);
    LOAD_SYM(clReleaseEvent);
    LOAD_SYM(clReleaseMemObject);
    LOAD_SYM(clReleaseKernel);
    LOAD_SYM(clReleaseProgram);
    LOAD_SYM(clReleaseCommandQueue);
#undef LOAD_SYM

    cl_platform_id platforms[MAX_OPENCL_PLATFORMS];
    cl_uint num_platforms = 0;
    if (fn_clGetPlatformIDs(MAX_OPENCL_PLATFORMS, platforms, &num_platforms) != CL_SUCCESS || num_platforms == 0) {
        g_initialized = 1;
        g_device_count = 0;
        pthread_mutex_unlock(&g_compile_mutex);
        return 0;
    }

    for (cl_uint p = 0; p < num_platforms && g_device_count < MAX_OPENCL_DEVICES; p++) {
        cl_device_id devs[MAX_OPENCL_DEVICES];
        cl_uint num_devs = 0;
        if (fn_clGetDeviceIDs(platforms[p], CL_DEVICE_TYPE_GPU, MAX_OPENCL_DEVICES, devs, &num_devs) != CL_SUCCESS) {
            continue;
        }
        for (cl_uint d = 0; d < num_devs && g_device_count < MAX_OPENCL_DEVICES; d++) {
            GoldLeafDeviceState st;
            memset(&st, 0, sizeof(st));
            st.platform = platforms[p];
            st.device = devs[d];

            fn_clGetDeviceInfo(devs[d], CL_DEVICE_NAME, sizeof(st.name) - 1, st.name, NULL);
            fn_clGetDeviceInfo(devs[d], CL_DEVICE_VENDOR, sizeof(st.vendor) - 1, st.vendor, NULL);
            fn_clGetDeviceInfo(devs[d], CL_DRIVER_VERSION, sizeof(st.driver_version) - 1, st.driver_version, NULL);

            if (is_blacklisted_pocl_device(st.vendor, st.name)) {
                continue;
            }

            char ext_buf[4096];
            memset(ext_buf, 0, sizeof(ext_buf));
            if (fn_clGetDeviceInfo(devs[d], CL_DEVICE_EXTENSIONS, sizeof(ext_buf) - 1, ext_buf, NULL) == CL_SUCCESS) {
                st.supports_fp64 = (strstr(ext_buf, "cl_khr_fp64") != NULL) ? 1 : 0;
            }
            size_t max_wg = 128;
            if (fn_clGetDeviceInfo(devs[d], CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(size_t), &max_wg, NULL) != CL_SUCCESS || max_wg == 0) {
                max_wg = 128;
            }
            st.max_work_group_size = max_wg;
            st.preferred_local_size = max_wg >= 128 ? 128 : (max_wg >= 64 ? 64 : (max_wg >= 32 ? 32 : 1));

            cl_int err = CL_SUCCESS;
            st.context = fn_clCreateContext(NULL, 1, &devs[d], NULL, NULL, &err);
            if (!st.context || err != CL_SUCCESS) {
                continue;
            }
            int slots_ok = 1;
            for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                st.slots[s].queue = fn_clCreateCommandQueue(st.context, devs[d], CL_QUEUE_PROFILING_ENABLE, &err);
                if (!st.slots[s].queue || err != CL_SUCCESS) {
                    st.slots[s].queue = fn_clCreateCommandQueue(st.context, devs[d], 0, &err);
                }
                if (!st.slots[s].queue || err != CL_SUCCESS) {
                    slots_ok = 0;
                    break;
                }
                pthread_mutex_init(&st.slots[s].slot_mutex, NULL);
                st.slots[s].mutex_initialized = 1;
            }
            if (!slots_ok) {
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            st.dummy_spill_buf = fn_clCreateBuffer(st.context, CL_MEM_READ_ONLY, sizeof(int32_t), NULL, &err);
            if (!st.dummy_spill_buf || err != CL_SUCCESS) {
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            st.compute_queue = fn_clCreateCommandQueue(st.context, devs[d], CL_QUEUE_PROFILING_ENABLE, &err);
            if (!st.compute_queue || err != CL_SUCCESS) {
                st.compute_queue = fn_clCreateCommandQueue(st.context, devs[d], 0, &err);
            }
            if (!st.compute_queue || err != CL_SUCCESS) {
                fn_clReleaseMemObject(st.dummy_spill_buf);
                st.dummy_spill_buf = NULL;
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            st.compute_dummy_rw_buf = fn_clCreateBuffer(st.context, CL_MEM_READ_WRITE, sizeof(uint32_t), NULL, &err);
            if (!st.compute_dummy_rw_buf || err != CL_SUCCESS) {
                fn_clReleaseCommandQueue(st.compute_queue);
                st.compute_queue = NULL;
                fn_clReleaseMemObject(st.dummy_spill_buf);
                st.dummy_spill_buf = NULL;
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            st.next_slot = 0;
            st.next_ore_slot = 0;
            int ore_slots_ok = 1;
            for (int os = 0; os < GOLDLEAF_ORE_SLOTS; os++) {
                st.ore_slots[os].queue = fn_clCreateCommandQueue(st.context, devs[d], CL_QUEUE_PROFILING_ENABLE, &err);
                if (!st.ore_slots[os].queue || err != CL_SUCCESS) {
                    st.ore_slots[os].queue = fn_clCreateCommandQueue(st.context, devs[d], 0, &err);
                }
                if (!st.ore_slots[os].queue || err != CL_SUCCESS) {
                    ore_slots_ok = 0;
                    break;
                }
            }
            if (!ore_slots_ok) {
                for (int os = 0; os < GOLDLEAF_ORE_SLOTS; os++) {
                    release_ore_slot_locked(&st.ore_slots[os]);
                }
                fn_clReleaseMemObject(st.compute_dummy_rw_buf);
                fn_clReleaseCommandQueue(st.compute_queue);
                fn_clReleaseMemObject(st.dummy_spill_buf);
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            st.next_light_slot = 0;
            int light_slots_ok = 1;
            for (int ls = 0; ls < GOLDLEAF_LIGHT_SLOTS; ls++) {
                st.light_slots[ls].queue = fn_clCreateCommandQueue(st.context, devs[d], CL_QUEUE_PROFILING_ENABLE, &err);
                if (!st.light_slots[ls].queue || err != CL_SUCCESS) {
                    st.light_slots[ls].queue = fn_clCreateCommandQueue(st.context, devs[d], 0, &err);
                }
                if (!st.light_slots[ls].queue || err != CL_SUCCESS) {
                    light_slots_ok = 0;
                    break;
                }
            }
            if (!light_slots_ok) {
                for (int ls = 0; ls < GOLDLEAF_LIGHT_SLOTS; ls++) {
                    release_light_slot_locked(&st.light_slots[ls]);
                }
                for (int os = 0; os < GOLDLEAF_ORE_SLOTS; os++) {
                    release_ore_slot_locked(&st.ore_slots[os]);
                }
                fn_clReleaseMemObject(st.compute_dummy_rw_buf);
                fn_clReleaseCommandQueue(st.compute_queue);
                fn_clReleaseMemObject(st.dummy_spill_buf);
                for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
                    release_dma_slot_locked(&st.slots[s]);
                }
                continue;
            }
            int idx = g_device_count++;
            g_devices[idx] = st;
            pthread_mutex_init(&g_devices[idx].compute_mutex, NULL);
            g_devices[idx].compute_mutex_initialized = 1;
            for (int os = 0; os < GOLDLEAF_ORE_SLOTS; os++) {
                pthread_mutex_init(&g_devices[idx].ore_slots[os].slot_mutex, NULL);
                g_devices[idx].ore_slots[os].mutex_initialized = 1;
            }
            for (int ls = 0; ls < GOLDLEAF_LIGHT_SLOTS; ls++) {
                pthread_mutex_init(&g_devices[idx].light_slots[ls].slot_mutex, NULL);
                g_devices[idx].light_slots[ls].mutex_initialized = 1;
            }
        }
    }

    g_initialized = 1;
    int count = g_device_count;
    pthread_mutex_unlock(&g_compile_mutex);
    return (jint)count;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDeviceCount(
    JNIEnv* env,
    jclass cls
) {
    (void)env;
    (void)cls;
    return (jint)g_device_count;
}

JNIEXPORT jstring JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDeviceName(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex
) {
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, g_devices[deviceIndex].name);
}

JNIEXPORT jstring JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDeviceSignature(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex
) {
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count) {
        return NULL;
    }
    char sig[512];
    snprintf(sig, sizeof(sig), "%s|%s|%s",
        g_devices[deviceIndex].vendor,
        g_devices[deviceIndex].name,
        g_devices[deviceIndex].driver_version);
    return (*env)->NewStringUTF(env, sig);
}

JNIEXPORT jboolean JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDeviceSupportsFp64(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex
) {
    (void)env;
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count) {
        return JNI_FALSE;
    }
    return g_devices[deviceIndex].supports_fp64 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeLastBuildLog(
    JNIEnv* env,
    jclass cls
) {
    (void)cls;
    pthread_mutex_lock(&g_compile_mutex);
    jstring res = (*env)->NewStringUTF(env, g_last_build_log);
    pthread_mutex_unlock(&g_compile_mutex);
    return res;
}

JNIEXPORT jlong JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeCompileFromSource(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex,
    jstring jSource,
    jstring jKernelName,
    jstring jBuildOpts
) {
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count || !jSource || !jKernelName) {
        return 0;
    }
    const char* src = (*env)->GetStringUTFChars(env, jSource, NULL);
    const char* kname = (*env)->GetStringUTFChars(env, jKernelName, NULL);
    const char* opts = jBuildOpts ? (*env)->GetStringUTFChars(env, jBuildOpts, NULL) : "";
    if (!src || !kname) {
        if (opts && jBuildOpts) (*env)->ReleaseStringUTFChars(env, jBuildOpts, opts);
        if (kname) (*env)->ReleaseStringUTFChars(env, jKernelName, kname);
        if (src) (*env)->ReleaseStringUTFChars(env, jSource, src);
        return 0;
    }

    size_t src_len = strlen(src);
    GoldLeafProgramHandle* handle = NULL;

    pthread_mutex_lock(&g_compile_mutex);
    GoldLeafDeviceState* dev = &g_devices[deviceIndex];
    cl_int err = CL_SUCCESS;
    cl_program prog = fn_clCreateProgramWithSource(dev->context, 1, &src, &src_len, &err);
    if (prog && err == CL_SUCCESS) {
        err = fn_clBuildProgram(prog, 1, &dev->device, opts, NULL, NULL);
        if (err != CL_SUCCESS) {
            capture_build_log_locked(prog, dev->device);
            fn_clReleaseProgram(prog);
            prog = NULL;
        } else {
            cl_kernel kern = fn_clCreateKernel(prog, kname, &err);
            if (!kern || err != CL_SUCCESS) {
                snprintf(g_last_build_log, sizeof(g_last_build_log), "clCreateKernel(%s) failed: %d", kname, (int)err);
                fn_clReleaseProgram(prog);
                prog = NULL;
            } else {
                handle = (GoldLeafProgramHandle*)calloc(1, sizeof(GoldLeafProgramHandle));
                if (handle) {
                    handle->device_index = deviceIndex;
                    handle->program = prog;
                    handle->kernel = kern;
                    handle->terrain_aquifer_kernel = NULL;
                    if (strcmp(kname, "apply_carver_aquifer_u8") == 0) {
                        cl_int ta_err = CL_SUCCESS;
                        cl_kernel ta_kern = fn_clCreateKernel(prog, "apply_terrain_aquifer_u8", &ta_err);
                        if (ta_kern && ta_err == CL_SUCCESS) {
                            handle->terrain_aquifer_kernel = ta_kern;
                        }
                    }
                    handle->spill_buf = NULL;
                    pthread_mutex_init(&handle->kernel_arg_mutex, NULL);
                } else {
                    fn_clReleaseKernel(kern);
                    fn_clReleaseProgram(prog);
                }
            }
        }
    }
    pthread_mutex_unlock(&g_compile_mutex);

    if (jBuildOpts && opts) (*env)->ReleaseStringUTFChars(env, jBuildOpts, opts);
    (*env)->ReleaseStringUTFChars(env, jKernelName, kname);
    (*env)->ReleaseStringUTFChars(env, jSource, src);
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT jbyteArray JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeExportProgramBinary(
    JNIEnv* env,
    jclass cls,
    jlong jHandle
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->program) {
        return NULL;
    }
    jbyteArray result = NULL;
    pthread_mutex_lock(&g_compile_mutex);
    size_t bin_size = 0;
    if (fn_clGetProgramInfo(handle->program, CL_PROGRAM_BINARY_SIZES, sizeof(size_t), &bin_size, NULL) == CL_SUCCESS
        && bin_size > 0) {
        unsigned char* bin_buf = (unsigned char*)malloc(bin_size);
        if (bin_buf) {
            unsigned char* ptrs[1] = { bin_buf };
            if (fn_clGetProgramInfo(handle->program, CL_PROGRAM_BINARIES, sizeof(ptrs), ptrs, NULL) == CL_SUCCESS) {
                result = (*env)->NewByteArray(env, (jsize)bin_size);
                if (result) {
                    (*env)->SetByteArrayRegion(env, result, 0, (jsize)bin_size, (const jbyte*)bin_buf);
                }
            }
            free(bin_buf);
        }
    }
    pthread_mutex_unlock(&g_compile_mutex);
    return result;
}

JNIEXPORT jlong JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeCompileFromBinary(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex,
    jbyteArray jBinary,
    jstring jKernelName,
    jstring jBuildOpts
) {
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count || !jBinary || !jKernelName) {
        return 0;
    }
    jsize bin_len = (*env)->GetArrayLength(env, jBinary);
    if (bin_len <= 0) {
        return 0;
    }
    jbyte* bin_bytes = (*env)->GetByteArrayElements(env, jBinary, NULL);
    const char* kname = (*env)->GetStringUTFChars(env, jKernelName, NULL);
    const char* opts = jBuildOpts ? (*env)->GetStringUTFChars(env, jBuildOpts, NULL) : "";
    if (!bin_bytes || !kname) {
        if (opts && jBuildOpts) (*env)->ReleaseStringUTFChars(env, jBuildOpts, opts);
        if (kname) (*env)->ReleaseStringUTFChars(env, jKernelName, kname);
        if (bin_bytes) (*env)->ReleaseByteArrayElements(env, jBinary, bin_bytes, JNI_ABORT);
        return 0;
    }

    GoldLeafProgramHandle* handle = NULL;
    pthread_mutex_lock(&g_compile_mutex);
    GoldLeafDeviceState* dev = &g_devices[deviceIndex];
    size_t sizes[1] = { (size_t)bin_len };
    const unsigned char* ptrs[1] = { (const unsigned char*)bin_bytes };
    cl_int bin_status = CL_SUCCESS;
    cl_int err = CL_SUCCESS;
    cl_program prog = fn_clCreateProgramWithBinary(dev->context, 1, &dev->device, sizes, ptrs, &bin_status, &err);
    if (prog && err == CL_SUCCESS && bin_status == CL_SUCCESS) {
        err = fn_clBuildProgram(prog, 1, &dev->device, opts, NULL, NULL);
        if (err != CL_SUCCESS) {
            capture_build_log_locked(prog, dev->device);
            fn_clReleaseProgram(prog);
            prog = NULL;
        } else {
            cl_kernel kern = fn_clCreateKernel(prog, kname, &err);
            if (!kern || err != CL_SUCCESS) {
                snprintf(g_last_build_log, sizeof(g_last_build_log), "clCreateKernel(%s) from binary failed: %d", kname, (int)err);
                fn_clReleaseProgram(prog);
                prog = NULL;
            } else {
                handle = (GoldLeafProgramHandle*)calloc(1, sizeof(GoldLeafProgramHandle));
                if (handle) {
                    handle->device_index = deviceIndex;
                    handle->program = prog;
                    handle->kernel = kern;
                    handle->terrain_aquifer_kernel = NULL;
                    if (strcmp(kname, "apply_carver_aquifer_u8") == 0) {
                        cl_int ta_err = CL_SUCCESS;
                        cl_kernel ta_kern = fn_clCreateKernel(prog, "apply_terrain_aquifer_u8", &ta_err);
                        if (ta_kern && ta_err == CL_SUCCESS) {
                            handle->terrain_aquifer_kernel = ta_kern;
                        }
                    }
                    handle->spill_buf = NULL;
                    pthread_mutex_init(&handle->kernel_arg_mutex, NULL);
                } else {
                    fn_clReleaseKernel(kern);
                    fn_clReleaseProgram(prog);
                }
            }
        }
    } else {
        if (prog) {
            fn_clReleaseProgram(prog);
        }
        snprintf(g_last_build_log, sizeof(g_last_build_log),
            "clCreateProgramWithBinary rejected cached binary (err=%d, bin_status=%d)", (int)err, (int)bin_status);
    }
    pthread_mutex_unlock(&g_compile_mutex);

    if (jBuildOpts && opts) (*env)->ReleaseStringUTFChars(env, jBuildOpts, opts);
    (*env)->ReleaseStringUTFChars(env, jKernelName, kname);
    (*env)->ReleaseByteArrayElements(env, jBinary, bin_bytes, JNI_ABORT);
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeUploadSpillLut(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jSpillWords
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (!jSpillWords) {
        return 0;
    }
    jsize len = (*env)->GetArrayLength(env, jSpillWords);
    if (len <= 0) {
        return 0;
    }
    jint* words = (*env)->GetIntArrayElements(env, jSpillWords, NULL);
    if (!words) {
        return -2;
    }
    pthread_mutex_lock(&g_compile_mutex);
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    GpuDmaSlot* slot0 = &dev->slots[0];
    pthread_mutex_lock(&slot0->slot_mutex);
    if (handle->spill_buf) {
        fn_clReleaseMemObject(handle->spill_buf);
        handle->spill_buf = NULL;
    }
    cl_int err = CL_SUCCESS;
    size_t bytes = (size_t)len * sizeof(int32_t);
    cl_mem buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
    if (buf && err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot0->queue, buf, 1, 0, bytes, words, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            handle->spill_buf = buf;
        } else {
            fn_clReleaseMemObject(buf);
        }
    }
    pthread_mutex_unlock(&slot0->slot_mutex);
    pthread_mutex_unlock(&g_compile_mutex);
    (*env)->ReleaseIntArrayElements(env, jSpillWords, words, JNI_ABORT);
    return (jint)err;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeUploadBlockDictLut(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jDictIds
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (!jDictIds) {
        return 0;
    }
    jsize len = (*env)->GetArrayLength(env, jDictIds);
    if (len <= 0) {
        return 0;
    }
    jint* words = (*env)->GetIntArrayElements(env, jDictIds, NULL);
    if (!words) {
        return -2;
    }
    pthread_mutex_lock(&g_compile_mutex);
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    GpuDmaSlot* slot0 = &dev->slots[0];
    pthread_mutex_lock(&slot0->slot_mutex);
    if (handle->block_dict_buf) {
        fn_clReleaseMemObject(handle->block_dict_buf);
        handle->block_dict_buf = NULL;
    }
    cl_int err = CL_SUCCESS;
    size_t bytes = (size_t)len * sizeof(int32_t);
    cl_mem buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
    if (buf && err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot0->queue, buf, 1, 0, bytes, words, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            handle->block_dict_buf = buf;
        } else {
            fn_clReleaseMemObject(buf);
        }
    }
    pthread_mutex_unlock(&slot0->slot_mutex);
    pthread_mutex_unlock(&g_compile_mutex);
    (*env)->ReleaseIntArrayElements(env, jDictIds, words, JNI_ABORT);
    return (jint)err;
}

static jint dispatch_density_volume_batch_impl(
    JNIEnv* env,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sizeX,
    jint sizeY,
    jint sizeZ,
    jint stepX,
    jint stepY,
    jint stepZ,
    jfloatArray jOutValues,
    jlongArray jOutTimingsNanos,
    size_t channels_per_voxel
) {
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !jOriginsXyz4 || !jOutValues || channels_per_voxel == 0) {
        return -2;
    }
    size_t volume_size = (size_t)sizeX * (size_t)sizeY * (size_t)sizeZ;
    size_t total_voxels = (size_t)batchCount * volume_size;
    size_t total_floats = total_voxels * channels_per_voxel;
    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4
        || (size_t)(*env)->GetArrayLength(env, jOutValues) < total_floats) {
        return -3;
    }

    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    GpuDmaSlot* slot = lock_next_dma_slot(dev);

    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_out_values_locked(dev, slot, total_floats);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    if (slot->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sx = (int32_t)sizeX;
    int32_t arg_sy = (int32_t)sizeY;
    int32_t arg_sz = (int32_t)sizeZ;
    int32_t arg_stx = (int32_t)stepX;
    int32_t arg_sty = (int32_t)stepY;
    int32_t arg_stz = (int32_t)stepZ;

    size_t local_ws = dev->preferred_local_size > 0 ? dev->preferred_local_size : 128;
    size_t global_ws = ((total_voxels + local_ws - 1) / local_ws) * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();

    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->out_values_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_sx);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sy);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sz);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_stx);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_sty);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(int32_t), &arg_stz);
    err |= fn_clSetKernelArg(handle->kernel, 9, sizeof(cl_mem), &spill);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    if (slot->out_host_ptr) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->out_values_buf, 1, 0,
            total_floats * sizeof(float), slot->out_host_ptr, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            (*env)->SetFloatArrayRegion(env, jOutValues, 0, (jsize)total_floats, slot->out_host_ptr);
        }
    } else {
        jfloat* out_ptr = (*env)->GetFloatArrayElements(env, jOutValues, NULL);
        if (!out_ptr) {
            if (kern_ev) fn_clReleaseEvent(kern_ev);
            pthread_mutex_unlock(&slot->slot_mutex);
            return -7;
        }
        err = fn_clEnqueueReadBuffer(slot->queue, slot->out_values_buf, 1, 0,
            total_floats * sizeof(float), out_ptr, 0, NULL, NULL);
        (*env)->ReleaseFloatArrayElements(env, jOutValues, out_ptr, err == CL_SUCCESS ? 0 : JNI_ABORT);
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchDensityVolumeBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sizeX,
    jint sizeY,
    jint sizeZ,
    jint stepX,
    jint stepY,
    jint stepZ,
    jfloatArray jOutValues,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    return dispatch_density_volume_batch_impl(
        env, jHandle, jOriginsXyz4, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, jOutValues, jOutTimingsNanos, 1
    );
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchBiomeClimate6Batch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sizeX,
    jint sizeY,
    jint sizeZ,
    jint stepX,
    jint stepY,
    jint stepZ,
    jfloatArray jOutInterleaved6,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    return dispatch_density_volume_batch_impl(
        env, jHandle, jOriginsXyz4, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, jOutInterleaved6, jOutTimingsNanos, 6
    );
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchSectionPaletteFillBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sectionCount,
    jint seaLevel,
    jint deepFluidY,
    jobject jOutWordsDirectBuf,
    jobject jOutMetaDirectBuf,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || sectionCount <= 0 || !jOriginsXyz4 || !jOutWordsDirectBuf || !jOutMetaDirectBuf) {
        return -2;
    }
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    if (dev->max_work_group_size < 256) {
        return -8;
    }

    size_t total_sections = (size_t)batchCount * (size_t)sectionCount;
    size_t words_bytes = total_sections * 256 * sizeof(uint32_t);
    size_t meta_bytes = total_sections * 4 * sizeof(uint16_t);

    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4) {
        return -3;
    }
    void* out_words_ptr = (*env)->GetDirectBufferAddress(env, jOutWordsDirectBuf);
    jlong out_words_cap = (*env)->GetDirectBufferCapacity(env, jOutWordsDirectBuf);
    void* out_meta_ptr = (*env)->GetDirectBufferAddress(env, jOutMetaDirectBuf);
    jlong out_meta_cap = (*env)->GetDirectBufferCapacity(env, jOutMetaDirectBuf);
    if (!out_words_ptr || !out_meta_ptr || out_words_cap < (jlong)words_bytes || out_meta_cap < (jlong)meta_bytes) {
        return -9;
    }

    GpuDmaSlot* slot = lock_next_dma_slot(dev);

    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_compact_buffers_locked(dev, slot, total_sections, words_bytes, sizeof(uint32_t));
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    if (slot->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sections = (int32_t)sectionCount;
    int32_t arg_sea = (int32_t)seaLevel;
    int32_t arg_deep = (int32_t)deepFluidY;

    size_t local_ws = 256;
    size_t global_ws = total_sections * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();

    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sea);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_deep);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(cl_mem), &spill);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    void* words_dst = slot->compact_words_host_ptr ? (void*)slot->compact_words_host_ptr : out_words_ptr;
    void* meta_dst = slot->compact_meta_host_ptr ? (void*)slot->compact_meta_host_ptr : out_meta_ptr;
    err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_words_buf, 0, 0, words_bytes, words_dst, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_meta_buf, 1, 0, meta_bytes, meta_dst, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        if (slot->compact_words_host_ptr) {
            memcpy(out_words_ptr, slot->compact_words_host_ptr, words_bytes);
        }
        if (slot->compact_meta_host_ptr) {
            memcpy(out_meta_ptr, slot->compact_meta_host_ptr, meta_bytes);
        }
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchSurfaceSectionFill4BitBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sectionCount,
    jint seaLevel,
    jint deepFluidY,
    jint minBlockY,
    jobject jOutWordsDirectBuf,
    jobject jOutMetaDirectBuf,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || sectionCount <= 0 || !jOriginsXyz4 || !jOutWordsDirectBuf || !jOutMetaDirectBuf) {
        return -2;
    }
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    if (dev->max_work_group_size < 256) {
        return -8;
    }

    size_t total_sections = (size_t)batchCount * (size_t)sectionCount;
    size_t words_bytes = total_sections * 256 * sizeof(uint64_t);
    size_t meta_bytes = total_sections * 4 * sizeof(uint16_t);

    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4) {
        return -3;
    }
    void* out_words_ptr = (*env)->GetDirectBufferAddress(env, jOutWordsDirectBuf);
    jlong out_words_cap = (*env)->GetDirectBufferCapacity(env, jOutWordsDirectBuf);
    void* out_meta_ptr = (*env)->GetDirectBufferAddress(env, jOutMetaDirectBuf);
    jlong out_meta_cap = (*env)->GetDirectBufferCapacity(env, jOutMetaDirectBuf);
    if (!out_words_ptr || !out_meta_ptr || out_words_cap < (jlong)words_bytes || out_meta_cap < (jlong)meta_bytes) {
        return -9;
    }

    GpuDmaSlot* slot = lock_next_dma_slot(dev);

    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_compact_buffers_locked(dev, slot, total_sections, words_bytes, sizeof(uint64_t));
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    if (slot->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sections = (int32_t)sectionCount;
    int32_t arg_sea = (int32_t)seaLevel;
    int32_t arg_deep = (int32_t)deepFluidY;
    int32_t arg_min_y = (int32_t)minBlockY;

    size_t local_ws = 256;
    size_t global_ws = (size_t)batchCount * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();

    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sea);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_deep);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_min_y);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(cl_mem), &spill);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    void* words_dst = slot->compact_words_host_ptr ? (void*)slot->compact_words_host_ptr : out_words_ptr;
    void* meta_dst = slot->compact_meta_host_ptr ? (void*)slot->compact_meta_host_ptr : out_meta_ptr;
    err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_words_buf, 0, 0, words_bytes, words_dst, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_meta_buf, 1, 0, meta_bytes, meta_dst, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        if (slot->compact_words_host_ptr) {
            memcpy(out_words_ptr, slot->compact_words_host_ptr, words_bytes);
        }
        if (slot->compact_meta_host_ptr) {
            memcpy(out_meta_ptr, slot->compact_meta_host_ptr, meta_bytes);
        }
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchBiomeRTreeBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint sizeX,
    jint sizeY,
    jint sizeZ,
    jint stepX,
    jint stepY,
    jint stepZ,
    jbyteArray jOutBiomeIds,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !jOriginsXyz4 || !jOutBiomeIds) {
        return -2;
    }
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    size_t volume_size = (size_t)sizeX * (size_t)sizeY * (size_t)sizeZ;
    size_t total_voxels = (size_t)batchCount * volume_size;
    size_t out_floats_equiv = (total_voxels + sizeof(float) - 1) / sizeof(float);

    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4) {
        return -3;
    }
    if ((size_t)(*env)->GetArrayLength(env, jOutBiomeIds) < total_voxels) {
        return -5;
    }

    GpuDmaSlot* slot = lock_next_dma_slot(dev);

    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_out_values_locked(dev, slot, out_floats_equiv);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    if (slot->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sx = (int32_t)sizeX;
    int32_t arg_sy = (int32_t)sizeY;
    int32_t arg_sz = (int32_t)sizeZ;
    int32_t arg_stx = (int32_t)stepX;
    int32_t arg_sty = (int32_t)stepY;
    int32_t arg_stz = (int32_t)stepZ;

    size_t local_ws = dev->preferred_local_size;
    if (local_ws == 0 || local_ws > dev->max_work_group_size) {
        local_ws = 64;
    }
    size_t global_ws = ((total_voxels + local_ws - 1) / local_ws) * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();

    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->out_values_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_sx);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sy);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sz);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_stx);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_sty);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(int32_t), &arg_stz);
    err |= fn_clSetKernelArg(handle->kernel, 9, sizeof(cl_mem), &spill);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    if (slot->out_host_ptr) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->out_values_buf, 1, 0, total_voxels, slot->out_host_ptr, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            (*env)->SetByteArrayRegion(env, jOutBiomeIds, 0, (jsize)total_voxels, (const jbyte*)slot->out_host_ptr);
        }
    } else {
        jbyte* out_ptr = (*env)->GetByteArrayElements(env, jOutBiomeIds, NULL);
        if (!out_ptr) {
            if (kern_ev) fn_clReleaseEvent(kern_ev);
            pthread_mutex_unlock(&slot->slot_mutex);
            return -6;
        }
        err = fn_clEnqueueReadBuffer(slot->queue, slot->out_values_buf, 1, 0, total_voxels, out_ptr, 0, NULL, NULL);
        (*env)->ReleaseByteArrayElements(env, jOutBiomeIds, out_ptr, err == CL_SUCCESS ? 0 : JNI_ABORT);
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

static cl_int ensure_slot_biomes_and_flags_locked(
    GoldLeafDeviceState* dev,
    GpuDmaSlot* slot,
    size_t biomes_bytes,
    size_t prelim_bytes,
    size_t flags_bytes
) {
    cl_int err = CL_SUCCESS;
    if (biomes_bytes > slot->biomes_halo_cap_bytes || !slot->biomes_halo_host) {
        if (slot->biomes_halo_buf) {
            fn_clReleaseMemObject(slot->biomes_halo_buf);
            slot->biomes_halo_buf = NULL;
        }
        free(slot->biomes_halo_host);
        slot->biomes_halo_host = NULL;
        slot->biomes_halo_host = (int8_t*)malloc(biomes_bytes);
        if (!slot->biomes_halo_host) {
            slot->biomes_halo_cap_bytes = 0;
            return -7;
        }
        slot->biomes_halo_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, biomes_bytes, NULL, &err);
        if (err != CL_SUCCESS || !slot->biomes_halo_buf) {
            free(slot->biomes_halo_host);
            slot->biomes_halo_host = NULL;
            slot->biomes_halo_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -10;
        }
        slot->biomes_halo_cap_bytes = biomes_bytes;
    }
    if (prelim_bytes > slot->prelim_corners_cap_bytes || !slot->prelim_corners_host) {
        if (slot->prelim_corners_buf) {
            fn_clReleaseMemObject(slot->prelim_corners_buf);
            slot->prelim_corners_buf = NULL;
        }
        free(slot->prelim_corners_host);
        slot->prelim_corners_host = NULL;
        slot->prelim_corners_host = (float*)malloc(prelim_bytes);
        if (!slot->prelim_corners_host) {
            slot->prelim_corners_cap_bytes = 0;
            return -7;
        }
        slot->prelim_corners_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, prelim_bytes, NULL, &err);
        if (err != CL_SUCCESS || !slot->prelim_corners_buf) {
            free(slot->prelim_corners_host);
            slot->prelim_corners_host = NULL;
            slot->prelim_corners_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -12;
        }
        slot->prelim_corners_cap_bytes = prelim_bytes;
    }
    if (flags_bytes > slot->chunk_flags_cap_bytes) {
        if (slot->chunk_flags_buf) {
            fn_clReleaseMemObject(slot->chunk_flags_buf);
            slot->chunk_flags_buf = NULL;
        }
        slot->chunk_flags_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, flags_bytes, NULL, &err);
        if (err != CL_SUCCESS || !slot->chunk_flags_buf) {
            slot->chunk_flags_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->chunk_flags_cap_bytes = flags_bytes;
    }
    size_t hm_bytes = flags_bytes * 512 * sizeof(uint16_t);
    if (hm_bytes > slot->heightmaps_cap_bytes) {
        if (slot->heightmaps_buf) {
            fn_clReleaseMemObject(slot->heightmaps_buf);
            slot->heightmaps_buf = NULL;
        }
        slot->heightmaps_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, hm_bytes, NULL, &err);
        if (err != CL_SUCCESS || !slot->heightmaps_buf) {
            slot->heightmaps_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->heightmaps_cap_bytes = hm_bytes;
    }
    return CL_SUCCESS;
}

static cl_int ensure_slot_beardifier_locked(
    GoldLeafDeviceState* dev,
    GpuDmaSlot* slot,
    size_t beard_meta_bytes,
    size_t beard_items_bytes
) {
    cl_int err = CL_SUCCESS;
    if (slot->beard_meta_cap_bytes < beard_meta_bytes || !slot->beard_meta_host) {
        if (slot->beard_meta_buf) {
            fn_clReleaseMemObject(slot->beard_meta_buf);
            slot->beard_meta_buf = NULL;
        }
        free(slot->beard_meta_host);
        slot->beard_meta_host = NULL;
        size_t alloc_meta = beard_meta_bytes < 512 ? 512 : beard_meta_bytes;
        slot->beard_meta_host = (int32_t*)malloc(alloc_meta);
        if (!slot->beard_meta_host) {
            slot->beard_meta_cap_bytes = 0;
            return -7;
        }
        slot->beard_meta_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, alloc_meta, NULL, &err);
        if (err != CL_SUCCESS || !slot->beard_meta_buf) {
            free(slot->beard_meta_host);
            slot->beard_meta_host = NULL;
            slot->beard_meta_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->beard_meta_cap_bytes = alloc_meta;
    }
    if (slot->beard_items_cap_bytes < beard_items_bytes || !slot->beard_items_host) {
        if (slot->beard_items_buf) {
            fn_clReleaseMemObject(slot->beard_items_buf);
            slot->beard_items_buf = NULL;
        }
        free(slot->beard_items_host);
        slot->beard_items_host = NULL;
        size_t alloc_items = beard_items_bytes < 4096 ? 4096 : beard_items_bytes;
        slot->beard_items_host = (int32_t*)malloc(alloc_items);
        if (!slot->beard_items_host) {
            slot->beard_items_cap_bytes = 0;
            return -7;
        }
        slot->beard_items_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, alloc_items, NULL, &err);
        if (err != CL_SUCCESS || !slot->beard_items_buf) {
            free(slot->beard_items_host);
            slot->beard_items_host = NULL;
            slot->beard_items_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->beard_items_cap_bytes = alloc_items;
    }
    return CL_SUCCESS;
}

static cl_int ensure_slot_carver_locked(
    GoldLeafDeviceState* dev,
    GpuDmaSlot* slot,
    size_t meta_bytes,
    size_t masks_bytes,
    size_t events_bytes,
    size_t counts_bytes
) {
    cl_int err = CL_SUCCESS;
    if (slot->carver_meta_cap_bytes < meta_bytes || !slot->carver_meta_host) {
        if (slot->carver_meta_buf) { fn_clReleaseMemObject(slot->carver_meta_buf); slot->carver_meta_buf = NULL; }
        free(slot->carver_meta_host);
        slot->carver_meta_host = NULL;
        size_t alloc = meta_bytes < 256 ? 256 : meta_bytes;
        slot->carver_meta_host = (int32_t*)malloc(alloc);
        if (!slot->carver_meta_host) { slot->carver_meta_cap_bytes = 0; return -7; }
        slot->carver_meta_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, alloc, NULL, &err);
        if (err != CL_SUCCESS || !slot->carver_meta_buf) {
            free(slot->carver_meta_host);
            slot->carver_meta_host = NULL;
            slot->carver_meta_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->carver_meta_cap_bytes = alloc;
    }
    if (slot->carver_masks_cap_bytes < masks_bytes || !slot->carver_masks_host) {
        if (slot->carver_masks_buf) { fn_clReleaseMemObject(slot->carver_masks_buf); slot->carver_masks_buf = NULL; }
        free(slot->carver_masks_host);
        slot->carver_masks_host = NULL;
        size_t alloc = masks_bytes < 16384 ? 16384 : masks_bytes;
        slot->carver_masks_host = (int32_t*)malloc(alloc);
        if (!slot->carver_masks_host) { slot->carver_masks_cap_bytes = 0; return -7; }
        slot->carver_masks_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, alloc, NULL, &err);
        if (err != CL_SUCCESS || !slot->carver_masks_buf) {
            free(slot->carver_masks_host);
            slot->carver_masks_host = NULL;
            slot->carver_masks_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->carver_masks_cap_bytes = alloc;
    }
    if (slot->carver_events_cap_bytes < events_bytes || !slot->carver_events_host) {
        if (slot->carver_events_buf) { fn_clReleaseMemObject(slot->carver_events_buf); slot->carver_events_buf = NULL; }
        free(slot->carver_events_host);
        slot->carver_events_host = NULL;
        size_t alloc = events_bytes < 32768 ? 32768 : events_bytes;
        slot->carver_events_host = (jint*)malloc(alloc);
        if (!slot->carver_events_host) { slot->carver_events_cap_bytes = 0; return -7; }
        slot->carver_events_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc, NULL, &err);
        if (err != CL_SUCCESS || !slot->carver_events_buf) {
            free(slot->carver_events_host);
            slot->carver_events_host = NULL;
            slot->carver_events_cap_bytes = 0;
            return err != CL_SUCCESS ? err : -11;
        }
        slot->carver_events_cap_bytes = alloc;
    }
    if (slot->carver_counts_cap_bytes < counts_bytes) {
        if (slot->carver_counts_buf) { fn_clReleaseMemObject(slot->carver_counts_buf); slot->carver_counts_buf = NULL; }
        size_t alloc = counts_bytes < 256 ? 256 : counts_bytes;
        slot->carver_counts_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_WRITE, alloc, NULL, &err);
        if (err != CL_SUCCESS || !slot->carver_counts_buf) { slot->carver_counts_cap_bytes = 0; return err != CL_SUCCESS ? err : -11; }
        slot->carver_counts_cap_bytes = alloc;
    }
    size_t aq_cache_bytes = (counts_bytes / sizeof(int32_t)) * GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK * sizeof(int32_t);
    if (aq_cache_bytes < GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK * sizeof(int32_t)) {
        aq_cache_bytes = GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK * sizeof(int32_t);
    }
    if (slot->aquifer_cells_cap_bytes < aq_cache_bytes) {
        if (slot->aquifer_cells_buf) { fn_clReleaseMemObject(slot->aquifer_cells_buf); slot->aquifer_cells_buf = NULL; }
        size_t alloc = aq_cache_bytes < (size_t)32 * GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK * sizeof(int32_t)
            ? (size_t)32 * GOLDLEAF_AQ_CACHE_INTS_PER_CHUNK * sizeof(int32_t)
            : aq_cache_bytes;
        slot->aquifer_cells_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_WRITE, alloc, NULL, &err);
        if (err != CL_SUCCESS || !slot->aquifer_cells_buf) { slot->aquifer_cells_cap_bytes = 0; return err != CL_SUCCESS ? err : -11; }
        slot->aquifer_cells_cap_bytes = alloc;
    }
    return CL_SUCCESS;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchVanillaSurfaceU8Batch(
    JNIEnv* env,
    jclass cls,
    jlong jTerrainHandle,
    jlong jSurfaceHandle,
    jlong jPackHandle,
    jlong jCarverMaskHandle,
    jlong jCarverHandle,
    jlong jFeatureHandle,
    jintArray jOriginsXyz4,
    jbyteArray jBiomesHalo,
    jfloatArray jPrelimCorners4,
    jintArray jBeardMeta2,
    jintArray jBeardItems8,
    jint beardItemCount,
    jintArray jCarverMeta,
    jintArray jCarverMasks,
    jint carvedChunkCount,
    jint wordsPerCol,
    jint minCarveY,
    jint maxCarveY,
    jint batchCount,
    jint sectionCount,
    jint seaLevel,
    jint deepFluidY,
    jint minBlockY,
    jlong biomeZoomSeed,
    jobject jOutVoxelsDirectBuf,
    jobject jOutMetaDirectBuf,
    jobject jOutHeightmapsDirectBuf,
    jbyteArray jOutChunkFlags,
    jintArray jOutCarverEvents,
    jintArray jOutCarverCounts,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* t_handle = (GoldLeafProgramHandle*)(uintptr_t)jTerrainHandle;
    GoldLeafProgramHandle* s_handle = (GoldLeafProgramHandle*)(uintptr_t)jSurfaceHandle;
    GoldLeafProgramHandle* p_handle = jPackHandle != 0 ? (GoldLeafProgramHandle*)(uintptr_t)jPackHandle : NULL;
    GoldLeafProgramHandle* cm_handle = jCarverMaskHandle != 0 ? (GoldLeafProgramHandle*)(uintptr_t)jCarverMaskHandle : NULL;
    GoldLeafProgramHandle* c_handle = jCarverHandle != 0 ? (GoldLeafProgramHandle*)(uintptr_t)jCarverHandle : NULL;
    GoldLeafProgramHandle* f_handle = jFeatureHandle != 0 ? (GoldLeafProgramHandle*)(uintptr_t)jFeatureHandle : NULL;
    if (!t_handle || !t_handle->kernel || !s_handle || !s_handle->kernel || !p_handle || !p_handle->kernel
        || t_handle->device_index < 0 || t_handle->device_index >= g_device_count
        || s_handle->device_index != t_handle->device_index
        || p_handle->device_index != t_handle->device_index) {
        return -1;
    }
    if (cm_handle && (!cm_handle->kernel || cm_handle->device_index != t_handle->device_index)) {
        return -1;
    }
    if (c_handle && (!c_handle->kernel || c_handle->device_index != t_handle->device_index)) {
        return -1;
    }
    if (f_handle && (!f_handle->kernel || f_handle->device_index != t_handle->device_index)) {
        return -1;
    }
    if (batchCount <= 0 || sectionCount <= 0 || !jOriginsXyz4 || !jBiomesHalo || !jPrelimCorners4
        || !jOutVoxelsDirectBuf || !jOutMetaDirectBuf || !jOutChunkFlags) {
        return -2;
    }
    GoldLeafDeviceState* dev = &g_devices[t_handle->device_index];
    if (dev->max_work_group_size < 256) {
        return -8;
    }

    size_t total_sections = (size_t)batchCount * (size_t)sectionCount;
    size_t voxels_bytes = total_sections * 4096;
    size_t meta_bytes = total_sections * 4 * sizeof(uint16_t);
    size_t biomes_bytes = (size_t)batchCount * 36 * (size_t)(sectionCount * 4);
    size_t prelim_bytes = (size_t)batchCount * 4 * sizeof(float);
    size_t flags_bytes = (size_t)batchCount;

    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4) {
        return -3;
    }
    if ((size_t)(*env)->GetArrayLength(env, jBiomesHalo) < biomes_bytes) {
        return -4;
    }
    if ((size_t)(*env)->GetArrayLength(env, jPrelimCorners4) < (size_t)batchCount * 4) {
        return -4;
    }
    if ((size_t)(*env)->GetArrayLength(env, jOutChunkFlags) < flags_bytes) {
        return -5;
    }
    void* out_voxels_ptr = (*env)->GetDirectBufferAddress(env, jOutVoxelsDirectBuf);
    jlong out_voxels_cap = (*env)->GetDirectBufferCapacity(env, jOutVoxelsDirectBuf);
    void* out_meta_ptr = (*env)->GetDirectBufferAddress(env, jOutMetaDirectBuf);
    jlong out_meta_cap = (*env)->GetDirectBufferCapacity(env, jOutMetaDirectBuf);
    size_t heightmaps_bytes = (size_t)batchCount * 512 * sizeof(uint16_t);
    void* out_hm_ptr = jOutHeightmapsDirectBuf ? (*env)->GetDirectBufferAddress(env, jOutHeightmapsDirectBuf) : NULL;
    jlong out_hm_cap = jOutHeightmapsDirectBuf ? (*env)->GetDirectBufferCapacity(env, jOutHeightmapsDirectBuf) : 0;
    if (!out_voxels_ptr || !out_meta_ptr || out_voxels_cap < (jlong)voxels_bytes || out_meta_cap < (jlong)meta_bytes) {
        return -9;
    }
    if (jOutHeightmapsDirectBuf && (!out_hm_ptr || out_hm_cap < (jlong)heightmaps_bytes)) {
        return -9;
    }

    GpuDmaSlot* slot = lock_next_dma_slot(dev);

    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_compact_buffers_locked(dev, slot, total_sections, voxels_bytes, 16);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    err = ensure_slot_biomes_and_flags_locked(dev, slot, biomes_bytes, prelim_bytes, flags_bytes);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    size_t beard_meta_bytes = (size_t)batchCount * 2 * sizeof(int32_t);
    size_t beard_items_bytes = (size_t)(beardItemCount > 0 ? beardItemCount : 1) * 8 * sizeof(int32_t);
    err = ensure_slot_beardifier_locked(dev, slot, beard_meta_bytes, beard_items_bytes);
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }
    size_t carver_meta_bytes = (size_t)batchCount * sizeof(int32_t);
    int effective_mask_chunks = cm_handle ? batchCount : (carvedChunkCount > 0 ? carvedChunkCount : 1);
    size_t carver_masks_bytes = (size_t)effective_mask_chunks * 256 * (size_t)(wordsPerCol > 0 ? wordsPerCol : 1) * sizeof(uint32_t);
    size_t carver_events_bytes = (size_t)batchCount * GOLDLEAF_MAX_CARVER_EVENTS * sizeof(uint32_t);
    size_t carver_counts_bytes = (size_t)batchCount * sizeof(int32_t);
    if (c_handle || cm_handle) {
        err = ensure_slot_carver_locked(dev, slot, carver_meta_bytes, carver_masks_bytes, carver_events_bytes, carver_counts_bytes);
        if (err != CL_SUCCESS) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return (jint)err;
        }
    }

    if (slot->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&slot->slot_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err == CL_SUCCESS) {
        (*env)->GetByteArrayRegion(env, jBiomesHalo, 0, (jsize)biomes_bytes, (jbyte*)slot->biomes_halo_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->biomes_halo_buf, 0, 0, biomes_bytes, slot->biomes_halo_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        (*env)->GetFloatArrayRegion(env, jPrelimCorners4, 0, (jsize)(batchCount * 4), (jfloat*)slot->prelim_corners_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->prelim_corners_buf, 0, 0, prelim_bytes, slot->prelim_corners_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && jBeardMeta2) {
        (*env)->GetIntArrayRegion(env, jBeardMeta2, 0, (jsize)(batchCount * 2), (jint*)slot->beard_meta_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->beard_meta_buf, 0, 0, beard_meta_bytes, slot->beard_meta_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && jBeardItems8 && beardItemCount > 0) {
        (*env)->GetIntArrayRegion(env, jBeardItems8, 0, (jsize)(beardItemCount * 8), (jint*)slot->beard_items_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->beard_items_buf, 0, 0, (size_t)beardItemCount * 8 * sizeof(int32_t), slot->beard_items_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && (c_handle || cm_handle) && jCarverMeta) {
        (*env)->GetIntArrayRegion(env, jCarverMeta, 0, (jsize)batchCount, (jint*)slot->carver_meta_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->carver_meta_buf, 0, 0, carver_meta_bytes, slot->carver_meta_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && c_handle && !cm_handle && jCarverMasks && carvedChunkCount > 0 && wordsPerCol > 0) {
        size_t actual_masks_ints = (size_t)carvedChunkCount * 256 * (size_t)wordsPerCol;
        size_t actual_masks_bytes = actual_masks_ints * sizeof(uint32_t);
        (*env)->GetIntArrayRegion(env, jCarverMasks, 0, (jsize)actual_masks_ints, (jint*)slot->carver_masks_host);
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->carver_masks_buf, 0, 0, actual_masks_bytes, slot->carver_masks_host, 0, NULL, NULL);
    }
    // Zero per-wave atomic output buffers asynchronously in-queue (chunk_flags, carver_masks for Pass 3A, carver_counts for Pass 3B)
    uint8_t zero_u8 = 0;
    uint32_t zero_u32 = 0;
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueFillBuffer(slot->queue, slot->chunk_flags_buf, &zero_u8, sizeof(uint8_t), 0, flags_bytes, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && cm_handle && c_handle) {
        err = fn_clEnqueueFillBuffer(slot->queue, slot->carver_masks_buf, &zero_u32, sizeof(uint32_t), 0, carver_masks_bytes, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && c_handle) {
        err = fn_clEnqueueFillBuffer(slot->queue, slot->carver_counts_buf, &zero_u32, sizeof(uint32_t), 0, carver_counts_bytes, 0, NULL, NULL);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    cl_mem t_spill = t_handle->spill_buf ? t_handle->spill_buf : dev->dummy_spill_buf;
    cl_mem s_spill = s_handle->spill_buf ? s_handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sections = (int32_t)sectionCount;
    int32_t arg_sea = (int32_t)seaLevel;
    int32_t arg_deep = (int32_t)deepFluidY;
    int32_t arg_min_y = (int32_t)minBlockY;
    int64_t arg_zoom_seed = (int64_t)biomeZoomSeed;

    size_t local_ws = 256;
    size_t local_ws_64 = 64;
    size_t terrain_global_ws = total_sections * local_ws;
    size_t surface_global_ws = (size_t)batchCount * 4 * local_ws_64;
    cl_event kern_ev1 = NULL;
    cl_event kern_ev1b = NULL;
    cl_event kern_ev2 = NULL;
    cl_event kern_ev3a = NULL;
    cl_event kern_ev3 = NULL;
    cl_event kern_ev4 = NULL;
    cl_event kern_ev5 = NULL;
    int32_t arg_use_aquifer = (c_handle && c_handle->terrain_aquifer_kernel) ? 1 : 0;
    int32_t arg_do_pack = (c_handle || f_handle) ? 0 : 1;
    int32_t arg_do_pack_carv = f_handle ? 0 : 1;
    uint64_t t_kern0 = monotonic_ns();

    // Pass 1: fill_terrain_u8 across all batchCount * sectionCount workgroups (full SM saturation)
    pthread_mutex_lock(&t_handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(t_handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
    err |= fn_clSetKernelArg(t_handle->kernel, 1, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(t_handle->kernel, 2, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(t_handle->kernel, 3, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(t_handle->kernel, 4, sizeof(int32_t), &arg_sea);
    err |= fn_clSetKernelArg(t_handle->kernel, 5, sizeof(int32_t), &arg_deep);
    err |= fn_clSetKernelArg(t_handle->kernel, 6, sizeof(int32_t), &arg_min_y);
    err |= fn_clSetKernelArg(t_handle->kernel, 7, sizeof(cl_mem), &slot->beard_meta_buf);
    err |= fn_clSetKernelArg(t_handle->kernel, 8, sizeof(cl_mem), &slot->beard_items_buf);
    err |= fn_clSetKernelArg(t_handle->kernel, 9, sizeof(cl_mem), &t_spill);
    err |= fn_clSetKernelArg(t_handle->kernel, 10, sizeof(int32_t), &arg_use_aquifer);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, t_handle->kernel, 1, NULL, &terrain_global_ws, &local_ws, 0, NULL, &kern_ev1);
    }
    pthread_mutex_unlock(&t_handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev1) fn_clReleaseEvent(kern_ev1);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    // Pass 1B (when aquifers enabled): apply_terrain_aquifer_u8 resolves Pass 1 sentinels via vanilla NoiseBasedAquifer
    // and caches the chunk's 3D Voronoi aquifer cells into slot->aquifer_cells_buf for Pass 3B reuse.
    if (arg_use_aquifer != 0) {
        cl_mem c_spill = c_handle->spill_buf ? c_handle->spill_buf : dev->dummy_spill_buf;
        size_t aq_global_ws = (size_t)batchCount * local_ws;
        pthread_mutex_lock(&c_handle->kernel_arg_mutex);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 1, sizeof(cl_mem), &slot->origins_buf);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 2, sizeof(cl_mem), &slot->carver_events_buf);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 3, sizeof(cl_mem), &slot->carver_counts_buf);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 4, sizeof(cl_mem), &slot->aquifer_cells_buf);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 5, sizeof(int32_t), &arg_batch);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 6, sizeof(int32_t), &arg_sections);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 7, sizeof(int32_t), &arg_sea);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 8, sizeof(int32_t), &arg_deep);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 9, sizeof(int32_t), &arg_min_y);
        err |= fn_clSetKernelArg(c_handle->terrain_aquifer_kernel, 10, sizeof(cl_mem), &c_spill);
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueNDRangeKernel(slot->queue, c_handle->terrain_aquifer_kernel, 1, NULL, &aq_global_ws, &local_ws, 0, NULL, &kern_ev1b);
        }
        pthread_mutex_unlock(&c_handle->kernel_arg_mutex);
        if (err != CL_SUCCESS) {
            if (kern_ev1) fn_clReleaseEvent(kern_ev1);
            if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
            pthread_mutex_unlock(&slot->slot_mutex);
            return (jint)err;
        }
    }

    // Pass 2: apply_vanilla_surface_u8 in-place on slot->compact_words_buf (zero finalDensity bloat)
    pthread_mutex_lock(&s_handle->kernel_arg_mutex);
    err |= fn_clSetKernelArg(s_handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 2, sizeof(cl_mem), &slot->chunk_flags_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 3, sizeof(cl_mem), &slot->origins_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 4, sizeof(cl_mem), &slot->biomes_halo_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 5, sizeof(cl_mem), &slot->prelim_corners_buf);
    err |= fn_clSetKernelArg(s_handle->kernel, 6, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(s_handle->kernel, 7, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(s_handle->kernel, 8, sizeof(int32_t), &arg_min_y);
    err |= fn_clSetKernelArg(s_handle->kernel, 9, sizeof(int64_t), &arg_zoom_seed);
    err |= fn_clSetKernelArg(s_handle->kernel, 10, sizeof(int32_t), &arg_do_pack);
    err |= fn_clSetKernelArg(s_handle->kernel, 11, sizeof(cl_mem), &s_spill);
    err |= fn_clSetKernelArg(s_handle->kernel, 12, sizeof(cl_mem), &slot->heightmaps_buf);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, s_handle->kernel, 1, NULL, &surface_global_ws, &local_ws_64, 0, NULL, &kern_ev2);
    }
    pthread_mutex_unlock(&s_handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev1) fn_clReleaseEvent(kern_ev1);
        if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
        if (kern_ev2) fn_clReleaseEvent(kern_ev2);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    // Pass 3A (optional): generate_carver_masks_u8 directly in VRAM (zero CPU CARVER_MASK, zero mask upload)
    if (cm_handle && c_handle) {
        int32_t arg_wpc = (int32_t)wordsPerCol;
        int32_t arg_min_cy = (int32_t)minCarveY;
        int32_t arg_max_cy = (int32_t)maxCarveY;
        pthread_mutex_lock(&cm_handle->kernel_arg_mutex);
        err |= fn_clSetKernelArg(cm_handle->kernel, 0, sizeof(cl_mem), &slot->origins_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 1, sizeof(cl_mem), &slot->carver_meta_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 2, sizeof(cl_mem), &slot->carver_masks_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 3, sizeof(int32_t), &arg_batch);
        err |= fn_clSetKernelArg(cm_handle->kernel, 4, sizeof(int32_t), &arg_wpc);
        err |= fn_clSetKernelArg(cm_handle->kernel, 5, sizeof(int32_t), &arg_min_y);
        err |= fn_clSetKernelArg(cm_handle->kernel, 6, sizeof(int32_t), &arg_min_cy);
        err |= fn_clSetKernelArg(cm_handle->kernel, 7, sizeof(int32_t), &arg_max_cy);
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueNDRangeKernel(slot->queue, cm_handle->kernel, 1, NULL, &surface_global_ws, &local_ws_64, 0, NULL, &kern_ev3a);
        }
        pthread_mutex_unlock(&cm_handle->kernel_arg_mutex);
        if (err != CL_SUCCESS) {
            if (kern_ev1) fn_clReleaseEvent(kern_ev1);
            if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
            if (kern_ev2) fn_clReleaseEvent(kern_ev2);
            if (kern_ev3a) fn_clReleaseEvent(kern_ev3a);
            pthread_mutex_unlock(&slot->slot_mutex);
            return (jint)err;
        }
    }

    // Pass 3B (optional): apply_carver_aquifer_u8 in-place on slot->compact_words_buf (+ optional 4-bit section packing)
    if (c_handle) {
        cl_mem c_spill = c_handle->spill_buf ? c_handle->spill_buf : dev->dummy_spill_buf;
        int32_t arg_wpc = (int32_t)wordsPerCol;
        pthread_mutex_lock(&c_handle->kernel_arg_mutex);
        err |= fn_clSetKernelArg(c_handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 2, sizeof(cl_mem), &slot->chunk_flags_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 3, sizeof(cl_mem), &slot->origins_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 4, sizeof(cl_mem), &slot->carver_meta_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 5, sizeof(cl_mem), &slot->carver_masks_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 6, sizeof(cl_mem), &slot->carver_events_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 7, sizeof(cl_mem), &slot->carver_counts_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 8, sizeof(int32_t), &arg_batch);
        err |= fn_clSetKernelArg(c_handle->kernel, 9, sizeof(int32_t), &arg_sections);
        err |= fn_clSetKernelArg(c_handle->kernel, 10, sizeof(int32_t), &arg_sea);
        err |= fn_clSetKernelArg(c_handle->kernel, 11, sizeof(int32_t), &arg_deep);
        err |= fn_clSetKernelArg(c_handle->kernel, 12, sizeof(int32_t), &arg_min_y);
        err |= fn_clSetKernelArg(c_handle->kernel, 13, sizeof(int32_t), &arg_wpc);
        err |= fn_clSetKernelArg(c_handle->kernel, 14, sizeof(int32_t), &arg_do_pack_carv);
        err |= fn_clSetKernelArg(c_handle->kernel, 15, sizeof(cl_mem), &c_spill);
        err |= fn_clSetKernelArg(c_handle->kernel, 16, sizeof(cl_mem), &slot->heightmaps_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 17, sizeof(cl_mem), &slot->aquifer_cells_buf);
        err |= fn_clSetKernelArg(c_handle->kernel, 18, sizeof(int32_t), &arg_use_aquifer);
        if (err == CL_SUCCESS) {
            size_t carver_global_ws = (size_t)batchCount * local_ws;
            err = fn_clEnqueueNDRangeKernel(slot->queue, c_handle->kernel, 1, NULL, &carver_global_ws, &local_ws, 0, NULL, &kern_ev3);
        }
        pthread_mutex_unlock(&c_handle->kernel_arg_mutex);
        if (err != CL_SUCCESS) {
            if (kern_ev1) fn_clReleaseEvent(kern_ev1);
            if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
            if (kern_ev2) fn_clReleaseEvent(kern_ev2);
            if (kern_ev3a) fn_clReleaseEvent(kern_ev3a);
            if (kern_ev3) fn_clReleaseEvent(kern_ev3);
            pthread_mutex_unlock(&slot->slot_mutex);
            return (jint)err;
        }
    }

    // Pass 4 (optional): apply_gpu_features_u8 in-place on slot->compact_words_buf + final 4-bit packing & heightmaps
    if (f_handle) {
        pthread_mutex_lock(&f_handle->kernel_arg_mutex);
        err |= fn_clSetKernelArg(f_handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
        err |= fn_clSetKernelArg(f_handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
        err |= fn_clSetKernelArg(f_handle->kernel, 2, sizeof(cl_mem), &slot->origins_buf);
        err |= fn_clSetKernelArg(f_handle->kernel, 3, sizeof(cl_mem), &slot->biomes_halo_buf);
        err |= fn_clSetKernelArg(f_handle->kernel, 4, sizeof(cl_mem), &slot->heightmaps_buf);
        err |= fn_clSetKernelArg(f_handle->kernel, 5, sizeof(int32_t), &arg_batch);
        err |= fn_clSetKernelArg(f_handle->kernel, 6, sizeof(int32_t), &arg_sections);
        err |= fn_clSetKernelArg(f_handle->kernel, 7, sizeof(int32_t), &arg_min_y);
        err |= fn_clSetKernelArg(f_handle->kernel, 8, sizeof(int32_t), &arg_sea);
        err |= fn_clSetKernelArg(f_handle->kernel, 9, sizeof(int64_t), &arg_zoom_seed);
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueNDRangeKernel(slot->queue, f_handle->kernel, 1, NULL, &surface_global_ws, &local_ws_64, 0, NULL, &kern_ev4);
        }
        pthread_mutex_unlock(&f_handle->kernel_arg_mutex);
        if (err != CL_SUCCESS) {
            if (kern_ev1) fn_clReleaseEvent(kern_ev1);
            if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
            if (kern_ev2) fn_clReleaseEvent(kern_ev2);
            if (kern_ev3a) fn_clReleaseEvent(kern_ev3a);
            if (kern_ev3) fn_clReleaseEvent(kern_ev3);
            if (kern_ev4) fn_clReleaseEvent(kern_ev4);
            pthread_mutex_unlock(&slot->slot_mutex);
            return (jint)err;
        }
    }

    // Pass 5: pack_sections_u8 across all batchCount * sectionCount workgroups (full SM saturation)
    pthread_mutex_lock(&p_handle->kernel_arg_mutex);
    cl_mem pack_dict_buf = p_handle->block_dict_buf ? p_handle->block_dict_buf : dev->dummy_spill_buf;
    int32_t arg_has_dict = p_handle->block_dict_buf ? 1 : 0;
    err |= fn_clSetKernelArg(p_handle->kernel, 0, sizeof(cl_mem), &slot->compact_words_buf);
    err |= fn_clSetKernelArg(p_handle->kernel, 1, sizeof(cl_mem), &slot->compact_meta_buf);
    err |= fn_clSetKernelArg(p_handle->kernel, 2, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(p_handle->kernel, 3, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(p_handle->kernel, 4, sizeof(cl_mem), &pack_dict_buf);
    err |= fn_clSetKernelArg(p_handle->kernel, 5, sizeof(int32_t), &arg_has_dict);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, p_handle->kernel, 1, NULL, &terrain_global_ws, &local_ws, 0, NULL, &kern_ev5);
    }
    pthread_mutex_unlock(&p_handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev1) fn_clReleaseEvent(kern_ev1);
        if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
        if (kern_ev2) fn_clReleaseEvent(kern_ev2);
        if (kern_ev3a) fn_clReleaseEvent(kern_ev3a);
        if (kern_ev3) fn_clReleaseEvent(kern_ev3);
        if (kern_ev4) fn_clReleaseEvent(kern_ev4);
        if (kern_ev5) fn_clReleaseEvent(kern_ev5);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    void* voxels_dst = slot->compact_words_host_ptr ? (void*)slot->compact_words_host_ptr : out_voxels_ptr;
    void* meta_dst = slot->compact_meta_host_ptr ? (void*)slot->compact_meta_host_ptr : out_meta_ptr;
    err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_words_buf, 0, 0, voxels_bytes, voxels_dst, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->compact_meta_buf, 0, 0, meta_bytes, meta_dst, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS && out_hm_ptr) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->heightmaps_buf, 0, 0, heightmaps_bytes, out_hm_ptr, 0, NULL, NULL);
    }
    jbyte flags_stack[64];
    jbyte* flags_tmp = flags_bytes <= 64 ? flags_stack : (jbyte*)malloc(flags_bytes);
    jint counts_stack[64];
    jint* counts_tmp = NULL;
    if (c_handle && jOutCarverCounts) {
        counts_tmp = (size_t)batchCount <= 64 ? counts_stack : (jint*)malloc(carver_counts_bytes);
        if (err == CL_SUCCESS && counts_tmp) {
            err = fn_clEnqueueReadBuffer(slot->queue, slot->carver_counts_buf, 0, 0, carver_counts_bytes, counts_tmp, 0, NULL, NULL);
        } else if (!counts_tmp) {
            err = -7;
        }
        if (err == CL_SUCCESS && jOutCarverEvents && slot->carver_events_host) {
            err = fn_clEnqueueReadBuffer(slot->queue, slot->carver_events_buf, 0, 0, carver_events_bytes, slot->carver_events_host, 0, NULL, NULL);
        }
    }
    if (err == CL_SUCCESS && flags_tmp) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->chunk_flags_buf, 1, 0, flags_bytes, flags_tmp, 0, NULL, NULL);
    } else if (!flags_tmp) {
        err = -7;
    }
    if (err == CL_SUCCESS && c_handle && counts_tmp && jOutCarverCounts) {
        for (int b = 0; b < batchCount; b++) {
            int cnt = counts_tmp[b];
            if (cnt > GOLDLEAF_MAX_CARVER_EVENTS) {
                if (flags_tmp) {
                    flags_tmp[b] = (jbyte)(flags_tmp[b] | 0x04);
                }
                counts_tmp[b] = GOLDLEAF_MAX_CARVER_EVENTS;
                cnt = GOLDLEAF_MAX_CARVER_EVENTS;
            }
            if (cnt > 0 && jOutCarverEvents && slot->carver_events_host) {
                (*env)->SetIntArrayRegion(env, jOutCarverEvents, (jsize)(b * GOLDLEAF_MAX_CARVER_EVENTS), (jsize)cnt, slot->carver_events_host + (size_t)b * GOLDLEAF_MAX_CARVER_EVENTS);
            }
        }
        (*env)->SetIntArrayRegion(env, jOutCarverCounts, 0, batchCount, counts_tmp);
    }
    if (counts_tmp && counts_tmp != counts_stack) {
        free(counts_tmp);
    }
    if (err == CL_SUCCESS) {
        if (slot->compact_meta_host_ptr) {
            memcpy(out_meta_ptr, slot->compact_meta_host_ptr, meta_bytes);
        }
        if (slot->compact_words_host_ptr) {
            const uint16_t* m = (const uint16_t*)meta_dst;
            const uint8_t* src_v = (const uint8_t*)slot->compact_words_host_ptr;
            uint8_t* dst_v = (uint8_t*)out_voxels_ptr;
            for (size_t s = 0; s < total_sections; s++) {
                uint16_t non_empty = m[s * 4 + 0];
                uint16_t mode = m[s * 4 + 1];
                if (non_empty == 0 || mode == 1) {
                    continue;
                }
                if (mode == 2) {
                    const uint8_t* sec_src = src_v + s * 4096;
                    uint8_t* sec_dst = dst_v + s * 4096;
                    uint8_t flat_bits = sec_src[2058];
                    size_t flat_bytes = (flat_bits >= 1 && flat_bits <= 4) ? ((size_t)flat_bits * 512U) : 2048U;
                    memcpy(sec_dst, sec_src, flat_bytes);
                    memcpy(sec_dst + 2048, sec_src + 2048, 88);
                } else {
                    memcpy(dst_v + s * 4096, src_v + s * 4096, 4096);
                }
            }
        }
        (*env)->SetByteArrayRegion(env, jOutChunkFlags, 0, (jsize)flags_bytes, flags_tmp);
    }
    if (flags_tmp && flags_tmp != flags_stack) {
        free(flags_tmp);
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    cl_event end_ev = kern_ev5 ? kern_ev5 : (kern_ev4 ? kern_ev4 : (kern_ev3 ? kern_ev3 : kern_ev2));
    if (kern_ev1 && end_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev1, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(end_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
    }
    if (kern_ev1) fn_clReleaseEvent(kern_ev1);
    if (kern_ev1b) fn_clReleaseEvent(kern_ev1b);
    if (kern_ev2) fn_clReleaseEvent(kern_ev2);
    if (kern_ev3a) fn_clReleaseEvent(kern_ev3a);
    if (kern_ev3) fn_clReleaseEvent(kern_ev3);
    if (kern_ev4) fn_clReleaseEvent(kern_ev4);
    if (kern_ev5) fn_clReleaseEvent(kern_ev5);
    uint64_t wait_and_read_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;
    uint64_t readback_ns = wait_and_read_ns > gpu_kern_ns ? (wait_and_read_ns - gpu_kern_ns) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchCarverMaskBatch(
    JNIEnv* env,
    jclass cls,
    jlong jCarverMaskHandle,
    jintArray jOriginsXyz4,
    jint batchCount,
    jint wordsPerCol,
    jint minBlockY,
    jint minCarveY,
    jint maxCarveY,
    jintArray jOutCarverMeta,
    jintArray jOutCarverMasks
) {
    (void)cls;
    GoldLeafProgramHandle* cm_handle = (GoldLeafProgramHandle*)(uintptr_t)jCarverMaskHandle;
    if (!cm_handle || !cm_handle->kernel || cm_handle->device_index < 0 || cm_handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || wordsPerCol <= 0 || !jOriginsXyz4 || !jOutCarverMeta || !jOutCarverMasks) {
        return -2;
    }
    GoldLeafDeviceState* dev = &g_devices[cm_handle->device_index];
    GpuDmaSlot* slot = lock_next_dma_slot(dev);
    cl_int err = ensure_slot_origins_locked(dev, slot, (size_t)batchCount);
    size_t meta_bytes = (size_t)batchCount * sizeof(int32_t);
    size_t mask_ints = (size_t)batchCount * 256 * (size_t)wordsPerCol;
    size_t mask_bytes = mask_ints * sizeof(uint32_t);
    if (err == CL_SUCCESS) {
        err = ensure_slot_carver_locked(dev, slot, meta_bytes, mask_bytes, (size_t)batchCount * 4, meta_bytes);
    }
    if (err == CL_SUCCESS) {
        if (slot->origins_host_ptr) {
            (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)slot->origins_host_ptr);
            err = fn_clEnqueueWriteBuffer(slot->queue, slot->origins_buf, 0, 0, (size_t)batchCount * 4 * sizeof(int32_t), slot->origins_host_ptr, 0, NULL, NULL);
        }
        int32_t neg1_i32 = -1;
        uint32_t zero_u32 = 0;
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueFillBuffer(slot->queue, slot->carver_meta_buf, &neg1_i32, sizeof(int32_t), 0, meta_bytes, 0, NULL, NULL);
        }
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueFillBuffer(slot->queue, slot->carver_masks_buf, &zero_u32, sizeof(uint32_t), 0, mask_bytes, 0, NULL, NULL);
        }
    }
    if (err == CL_SUCCESS) {
        int32_t arg_batch = (int32_t)batchCount;
        int32_t arg_wpc = (int32_t)wordsPerCol;
        int32_t arg_min_y = (int32_t)minBlockY;
        int32_t arg_min_cy = (int32_t)minCarveY;
        int32_t arg_max_cy = (int32_t)maxCarveY;
        size_t local_ws = 64;
        size_t global_ws = (size_t)batchCount * 4 * local_ws;
        pthread_mutex_lock(&cm_handle->kernel_arg_mutex);
        err |= fn_clSetKernelArg(cm_handle->kernel, 0, sizeof(cl_mem), &slot->origins_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 1, sizeof(cl_mem), &slot->carver_meta_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 2, sizeof(cl_mem), &slot->carver_masks_buf);
        err |= fn_clSetKernelArg(cm_handle->kernel, 3, sizeof(int32_t), &arg_batch);
        err |= fn_clSetKernelArg(cm_handle->kernel, 4, sizeof(int32_t), &arg_wpc);
        err |= fn_clSetKernelArg(cm_handle->kernel, 5, sizeof(int32_t), &arg_min_y);
        err |= fn_clSetKernelArg(cm_handle->kernel, 6, sizeof(int32_t), &arg_min_cy);
        err |= fn_clSetKernelArg(cm_handle->kernel, 7, sizeof(int32_t), &arg_max_cy);
        if (err == CL_SUCCESS) {
            err = fn_clEnqueueNDRangeKernel(slot->queue, cm_handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, NULL);
        }
        pthread_mutex_unlock(&cm_handle->kernel_arg_mutex);
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->carver_meta_buf, 0, 0, meta_bytes, slot->carver_meta_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->carver_masks_buf, 1, 0, mask_bytes, slot->carver_masks_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        (*env)->SetIntArrayRegion(env, jOutCarverMeta, 0, batchCount, (const jint*)slot->carver_meta_host);
        (*env)->SetIntArrayRegion(env, jOutCarverMasks, 0, (jsize)mask_ints, (const jint*)slot->carver_masks_host);
    }
    pthread_mutex_unlock(&slot->slot_mutex);
    return (jint)err;
}

static cl_int ensure_ore_buf_pair_locked(
    GoldLeafDeviceState* dev,
    cl_mem* buf_ptr,
    void** host_ptr,
    size_t* cap_ptr,
    cl_bitfield flags,
    size_t req_bytes,
    size_t min_bytes
) {
    if (*buf_ptr && *host_ptr && *cap_ptr >= req_bytes) {
        return CL_SUCCESS;
    }
    if (*buf_ptr) {
        fn_clReleaseMemObject(*buf_ptr);
        *buf_ptr = NULL;
    }
    free(*host_ptr);
    *host_ptr = NULL;
    *cap_ptr = 0;
    size_t alloc_bytes = req_bytes < min_bytes ? min_bytes : req_bytes;
    void* h = malloc(alloc_bytes);
    if (!h) {
        return -7;
    }
    cl_int err = CL_SUCCESS;
    *buf_ptr = fn_clCreateBuffer(dev->context, flags, alloc_bytes, NULL, &err);
    if (!(*buf_ptr) || err != CL_SUCCESS) {
        free(h);
        return err != CL_SUCCESS ? err : -5;
    }
    *host_ptr = h;
    *cap_ptr = alloc_bytes;
    return CL_SUCCESS;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchOreVeinRasterBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jVeinMeta4,
    jfloatArray jVeinRel6,
    jlongArray jVeinSeeds2,
    jint jVeinCount,
    jint jRegionMinX,
    jint jRegionMinZ,
    jint jMinBuildY,
    jint jMaxBuildY,
    jintArray jOutSlices2,
    jintArray jOutVoxels,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || jVeinCount <= 0
        || !jVeinMeta4 || !jVeinRel6 || !jVeinSeeds2 || !jOutSlices2 || !jOutVoxels) {
        return -1;
    }
    if (handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -2;
    }
    jsize max_voxels = (*env)->GetArrayLength(env, jOutVoxels);
    if (max_voxels <= 0) {
        return -3;
    }
    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    uint32_t start_slot = __sync_fetch_and_add(&dev->next_ore_slot, 1U) & (GOLDLEAF_ORE_SLOTS - 1U);
    OreDmaSlot* slot = NULL;
    for (int attempt = 0; attempt < GOLDLEAF_ORE_SLOTS; attempt++) {
        OreDmaSlot* cand = &dev->ore_slots[(start_slot + (uint32_t)attempt) & (GOLDLEAF_ORE_SLOTS - 1U)];
        if (pthread_mutex_trylock(&cand->slot_mutex) == 0) {
            slot = cand;
            break;
        }
    }
    if (!slot) {
        return -99; /* all GPU ore slots busy; caller steals work on CPU without blocking */
    }

    size_t vein_cnt = (size_t)jVeinCount;
    size_t meta_bytes = vein_cnt * 4 * sizeof(int32_t);
    size_t rel6_bytes = vein_cnt * 6 * sizeof(float);
    size_t seeds_bytes = vein_cnt * 2 * sizeof(uint64_t);
    size_t slices_bytes = vein_cnt * 2 * sizeof(int32_t);
    size_t voxels_cap_bytes = (size_t)max_voxels * sizeof(uint32_t);

    cl_int err = ensure_ore_buf_pair_locked(dev, &slot->ore_meta_buf, (void**)&slot->ore_meta_host, &slot->ore_meta_cap_bytes, CL_MEM_READ_ONLY, meta_bytes, 8192);
    if (err == CL_SUCCESS) {
        err = ensure_ore_buf_pair_locked(dev, &slot->ore_rel6_buf, (void**)&slot->ore_rel6_host, &slot->ore_rel6_cap_bytes, CL_MEM_READ_ONLY, rel6_bytes, 12288);
    }
    if (err == CL_SUCCESS) {
        err = ensure_ore_buf_pair_locked(dev, &slot->ore_seeds_buf, (void**)&slot->ore_seeds_host, &slot->ore_seeds_cap_bytes, CL_MEM_READ_ONLY, seeds_bytes, 8192);
    }
    if (err == CL_SUCCESS) {
        err = ensure_ore_buf_pair_locked(dev, &slot->ore_slices_buf, (void**)&slot->ore_slices_host, &slot->ore_slices_cap_bytes, CL_MEM_WRITE_ONLY, slices_bytes, 4096);
    }
    if (err == CL_SUCCESS) {
        err = ensure_ore_buf_pair_locked(dev, &slot->ore_voxels_buf, (void**)&slot->ore_voxels_host, &slot->ore_voxels_cap_bytes, CL_MEM_WRITE_ONLY, voxels_cap_bytes, 262144);
    }
    if (err == CL_SUCCESS && !slot->ore_counter_buf) {
        slot->ore_counter_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_WRITE, sizeof(uint32_t), NULL, &err);
        if (!slot->ore_counter_buf && err == CL_SUCCESS) {
            err = -5;
        }
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    (*env)->GetIntArrayRegion(env, jVeinMeta4, 0, (jsize)(vein_cnt * 4), (jint*)slot->ore_meta_host);
    (*env)->GetFloatArrayRegion(env, jVeinRel6, 0, (jsize)(vein_cnt * 6), (jfloat*)slot->ore_rel6_host);
    (*env)->GetLongArrayRegion(env, jVeinSeeds2, 0, (jsize)(vein_cnt * 2), (jlong*)slot->ore_seeds_host);

    slot->ore_zero_val = 0U;
    err = fn_clEnqueueWriteBuffer(slot->queue, slot->ore_meta_buf, 0, 0, meta_bytes, slot->ore_meta_host, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->ore_rel6_buf, 0, 0, rel6_bytes, slot->ore_rel6_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->ore_seeds_buf, 0, 0, seeds_bytes, slot->ore_seeds_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->ore_counter_buf, 0, 0, sizeof(uint32_t), &slot->ore_zero_val, 0, NULL, NULL);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    int32_t arg_vein_count = (int32_t)jVeinCount;
    int32_t arg_reg_min_x = (int32_t)jRegionMinX;
    int32_t arg_reg_min_z = (int32_t)jRegionMinZ;
    int32_t arg_min_y = (int32_t)jMinBuildY;
    int32_t arg_max_y = (int32_t)jMaxBuildY;
    int32_t arg_max_vox = (int32_t)max_voxels;
    size_t local_ws = 128;
    size_t global_ws = vein_cnt * local_ws;
    cl_event kern_ev = NULL;

    uint64_t t_kern0 = monotonic_ns();
    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err = fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->ore_meta_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->ore_rel6_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &slot->ore_seeds_buf);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(cl_mem), &slot->ore_slices_buf);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(cl_mem), &slot->ore_voxels_buf);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(cl_mem), &slot->ore_counter_buf);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_vein_count);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_reg_min_x);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(int32_t), &arg_reg_min_z);
    err |= fn_clSetKernelArg(handle->kernel, 9, sizeof(int32_t), &arg_min_y);
    err |= fn_clSetKernelArg(handle->kernel, 10, sizeof(int32_t), &arg_max_y);
    err |= fn_clSetKernelArg(handle->kernel, 11, sizeof(int32_t), &arg_max_vox);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    uint32_t total_voxels = 0U;
    uint32_t pre_read = (uint32_t)max_voxels < 12288U ? (uint32_t)max_voxels : 12288U;
    err = fn_clEnqueueReadBuffer(slot->queue, slot->ore_slices_buf, 0, 0, slices_bytes, slot->ore_slices_host, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(
            slot->queue,
            slot->ore_voxels_buf,
            0,
            0,
            (size_t)pre_read * sizeof(uint32_t),
            slot->ore_voxels_host,
            0,
            NULL,
            NULL
        );
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->ore_counter_buf, 1, 0, sizeof(uint32_t), &total_voxels, 0, NULL, NULL);
    }
    uint32_t clamped_voxels = total_voxels > (uint32_t)max_voxels ? (uint32_t)max_voxels : total_voxels;
    if (err == CL_SUCCESS && clamped_voxels > pre_read) {
        err = fn_clEnqueueReadBuffer(
            slot->queue,
            slot->ore_voxels_buf,
            1,
            (size_t)pre_read * sizeof(uint32_t),
            (size_t)(clamped_voxels - pre_read) * sizeof(uint32_t),
            slot->ore_voxels_host + pre_read,
            0,
            NULL,
            NULL
        );
    }
    if (err == CL_SUCCESS) {
        (*env)->SetIntArrayRegion(env, jOutSlices2, 0, (jsize)(vein_cnt * 2), (const jint*)slot->ore_slices_host);
        if (clamped_voxels > 0U) {
            (*env)->SetIntArrayRegion(env, jOutVoxels, 0, (jsize)clamped_voxels, (const jint*)slot->ore_voxels_host);
        }
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return err == CL_SUCCESS ? (jint)clamped_voxels : (jint)err;
}

static cl_int ensure_light_buf_and_host(
    GoldLeafDeviceState* dev,
    cl_mem* buf_slot,
    void** host_slot,
    size_t* cap_slot,
    cl_bitfield flags,
    size_t required_bytes
) {
    if (required_bytes == 0) {
        required_bytes = 64;
    }
    if (*buf_slot && (!host_slot || *host_slot) && *cap_slot >= required_bytes) {
        return CL_SUCCESS;
    }
    if (*buf_slot) {
        fn_clReleaseMemObject(*buf_slot);
        *buf_slot = NULL;
    }
    if (host_slot && *host_slot) {
        free(*host_slot);
        *host_slot = NULL;
    }
    *cap_slot = 0;
    size_t alloc_bytes = required_bytes < 4096 ? 4096 : required_bytes;
    cl_int err = CL_SUCCESS;
    cl_mem fresh = fn_clCreateBuffer(dev->context, flags, alloc_bytes, NULL, &err);
    if (!fresh || err != CL_SUCCESS) {
        return err != CL_SUCCESS ? err : -6;
    }
    if (host_slot) {
        void* h = malloc(alloc_bytes);
        if (!h) {
            fn_clReleaseMemObject(fresh);
            return -7;
        }
        *host_slot = h;
    }
    *buf_slot = fresh;
    *cap_slot = alloc_bytes;
    return CL_SUCCESS;
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchLightBatch(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jintArray jMeta4,
    jbyteArray jVoxelInfo,
    jint jBatchCount,
    jint jTotalSections,
    jboolean jReadBlockLight,
    jbyteArray jOutSkyNibbles,
    jbyteArray jOutBlkNibbles,
    jbyteArray jOutSecFlags,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (jBatchCount <= 0 || jTotalSections <= 0 || !jMeta4 || !jVoxelInfo || !jOutSkyNibbles || !jOutSecFlags) {
        return -2;
    }

    size_t batch_cnt = (size_t)jBatchCount;
    size_t total_secs = (size_t)jTotalSections;
    size_t meta_bytes = batch_cnt * 4U * sizeof(int32_t);
    size_t voxel_bytes = total_secs * 4096U;
    size_t nibble_bytes = total_secs * 2048U;
    size_t flags_bytes = total_secs;

    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    uint32_t start_slot = __sync_fetch_and_add(&dev->next_light_slot, 1U) & (GOLDLEAF_LIGHT_SLOTS - 1U);
    LightDmaSlot* slot = NULL;
    for (int attempt = 0; attempt < GOLDLEAF_LIGHT_SLOTS; attempt++) {
        LightDmaSlot* cand = &dev->light_slots[(start_slot + (uint32_t)attempt) & (GOLDLEAF_LIGHT_SLOTS - 1U)];
        if (pthread_mutex_trylock(&cand->slot_mutex) == 0) {
            slot = cand;
            break;
        }
    }
    if (!slot) {
        slot = &dev->light_slots[start_slot];
        pthread_mutex_lock(&slot->slot_mutex);
    }

    cl_int err = ensure_light_buf_and_host(dev, &slot->meta_buf, (void**)&slot->meta_host, &slot->meta_cap_bytes, CL_MEM_READ_ONLY, meta_bytes);
    if (err == CL_SUCCESS) {
        err = ensure_light_buf_and_host(dev, &slot->voxel_info_buf, (void**)&slot->voxel_info_host, &slot->voxel_info_cap_bytes, CL_MEM_READ_ONLY, voxel_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_light_buf_and_host(dev, &slot->light_grid_buf, NULL, &slot->light_grid_cap_bytes, CL_MEM_READ_WRITE, voxel_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_light_buf_and_host(dev, &slot->sky_nibbles_buf, (void**)&slot->sky_nibbles_host, &slot->sky_nibbles_cap_bytes, CL_MEM_WRITE_ONLY, nibble_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_light_buf_and_host(dev, &slot->blk_nibbles_buf, (void**)&slot->blk_nibbles_host, &slot->blk_nibbles_cap_bytes, CL_MEM_WRITE_ONLY, nibble_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_light_buf_and_host(dev, &slot->sec_flags_buf, (void**)&slot->sec_flags_host, &slot->sec_flags_cap_bytes, CL_MEM_WRITE_ONLY, flags_bytes);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    (*env)->GetIntArrayRegion(env, jMeta4, 0, (jsize)(batch_cnt * 4U), (jint*)slot->meta_host);
    (*env)->GetByteArrayRegion(env, jVoxelInfo, 0, (jsize)voxel_bytes, (jbyte*)slot->voxel_info_host);

    err = fn_clEnqueueWriteBuffer(slot->queue, slot->meta_buf, 0, 0, meta_bytes, slot->meta_host, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(slot->queue, slot->voxel_info_buf, 0, 0, voxel_bytes, slot->voxel_info_host, 0, NULL, NULL);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_kern0 = monotonic_ns();
    cl_event kern_ev = NULL;
    size_t local_size = 256U;
    size_t global_size = batch_cnt * 256U;
    int32_t batch_i32 = (int32_t)batch_cnt;

    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err = fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &slot->meta_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &slot->voxel_info_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &slot->light_grid_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 3, sizeof(cl_mem), &slot->sky_nibbles_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 4, sizeof(cl_mem), &slot->blk_nibbles_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 5, sizeof(cl_mem), &slot->sec_flags_buf);
    if (err == CL_SUCCESS) err = fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &batch_i32);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(slot->queue, handle->kernel, 1, NULL, &global_size, &local_size, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&slot->slot_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    err = fn_clEnqueueReadBuffer(slot->queue, slot->sky_nibbles_buf, 0, 0, nibble_bytes, slot->sky_nibbles_host, 0, NULL, NULL);
    if (err == CL_SUCCESS && jReadBlockLight && jOutBlkNibbles) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->blk_nibbles_buf, 0, 0, nibble_bytes, slot->blk_nibbles_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(slot->queue, slot->sec_flags_buf, 1, 0, flags_bytes, slot->sec_flags_host, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        (*env)->SetByteArrayRegion(env, jOutSecFlags, 0, (jsize)flags_bytes, (const jbyte*)slot->sec_flags_host);
        (*env)->SetByteArrayRegion(env, jOutSkyNibbles, 0, (jsize)nibble_bytes, (const jbyte*)slot->sky_nibbles_host);
        if (jReadBlockLight && jOutBlkNibbles) {
            (*env)->SetByteArrayRegion(env, jOutBlkNibbles, 0, (jsize)nibble_bytes, (const jbyte*)slot->blk_nibbles_host);
        }
    }
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 - t_kern0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&slot->slot_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

JNIEXPORT void JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeReleaseProgram(
    JNIEnv* env,
    jclass cls,
    jlong jHandle
) {
    (void)env;
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle) {
        return;
    }
    pthread_mutex_lock(&g_compile_mutex);
    if (handle->device_index >= 0 && handle->device_index < g_device_count) {
        GoldLeafDeviceState* dev = &g_devices[handle->device_index];
        for (int s = 0; s < GOLDLEAF_DMA_SLOTS; s++) {
            pthread_mutex_lock(&dev->slots[s].slot_mutex);
            if (dev->slots[s].queue) {
                fn_clFinish(dev->slots[s].queue);
            }
        }
        for (int os = 0; os < GOLDLEAF_ORE_SLOTS; os++) {
            if (dev->ore_slots[os].mutex_initialized) {
                pthread_mutex_lock(&dev->ore_slots[os].slot_mutex);
                if (dev->ore_slots[os].queue) {
                    fn_clFinish(dev->ore_slots[os].queue);
                }
            }
        }
        for (int ls = 0; ls < GOLDLEAF_LIGHT_SLOTS; ls++) {
            if (dev->light_slots[ls].mutex_initialized) {
                pthread_mutex_lock(&dev->light_slots[ls].slot_mutex);
                if (dev->light_slots[ls].queue) {
                    fn_clFinish(dev->light_slots[ls].queue);
                }
            }
        }
        if (dev->compute_mutex_initialized) {
            pthread_mutex_lock(&dev->compute_mutex);
            if (dev->compute_queue) {
                fn_clFinish(dev->compute_queue);
            }
        }
        if (handle->spill_buf) {
            fn_clReleaseMemObject(handle->spill_buf);
            handle->spill_buf = NULL;
        }
        if (handle->block_dict_buf) {
            fn_clReleaseMemObject(handle->block_dict_buf);
            handle->block_dict_buf = NULL;
        }
        if (dev->compute_mutex_initialized) {
            pthread_mutex_unlock(&dev->compute_mutex);
        }
        for (int ls = GOLDLEAF_LIGHT_SLOTS - 1; ls >= 0; ls--) {
            if (dev->light_slots[ls].mutex_initialized) {
                pthread_mutex_unlock(&dev->light_slots[ls].slot_mutex);
            }
        }
        for (int os = GOLDLEAF_ORE_SLOTS - 1; os >= 0; os--) {
            if (dev->ore_slots[os].mutex_initialized) {
                pthread_mutex_unlock(&dev->ore_slots[os].slot_mutex);
            }
        }
        for (int s = GOLDLEAF_DMA_SLOTS - 1; s >= 0; s--) {
            pthread_mutex_unlock(&dev->slots[s].slot_mutex);
        }
    }
    if (handle->terrain_aquifer_kernel) {
        fn_clReleaseKernel(handle->terrain_aquifer_kernel);
        handle->terrain_aquifer_kernel = NULL;
    }
    if (handle->kernel) {
        fn_clReleaseKernel(handle->kernel);
        handle->kernel = NULL;
    }
    if (handle->program) {
        fn_clReleaseProgram(handle->program);
        handle->program = NULL;
    }
    pthread_mutex_destroy(&handle->kernel_arg_mutex);
    free(handle);
    pthread_mutex_unlock(&g_compile_mutex);
}

static cl_int ensure_compute_buffer_locked(
    GoldLeafDeviceState* dev,
    cl_mem* buf_slot,
    size_t* cap_slot,
    cl_bitfield flags,
    size_t required_bytes
) {
    if (required_bytes == 0) {
        return CL_SUCCESS;
    }
    if (*buf_slot && *cap_slot >= required_bytes) {
        return CL_SUCCESS;
    }
    if (*buf_slot) {
        if (dev->compute_queue) {
            fn_clFinish(dev->compute_queue);
        }
        fn_clReleaseMemObject(*buf_slot);
        *buf_slot = NULL;
        *cap_slot = 0;
    }
    cl_int err = CL_SUCCESS;
    cl_mem fresh = fn_clCreateBuffer(dev->context, flags, required_bytes, NULL, &err);
    if (!fresh || err != CL_SUCCESS) {
        return err != CL_SUCCESS ? err : -6;
    }
    *buf_slot = fresh;
    *cap_slot = required_bytes;
    return CL_SUCCESS;
}

JNIEXPORT jlong JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeAllocComputeVramBuffer(
    JNIEnv* env,
    jclass cls,
    jint deviceIndex,
    jint jSizeBytes
) {
    (void)env;
    (void)cls;
    if (deviceIndex < 0 || deviceIndex >= g_device_count || jSizeBytes <= 0) {
        return 0;
    }
    size_t size_bytes = (size_t)jSizeBytes;
    GoldLeafDeviceState* dev = &g_devices[deviceIndex];
    pthread_mutex_lock(&g_compile_mutex);
    cl_int err = CL_SUCCESS;
    cl_mem buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_WRITE, size_bytes, NULL, &err);
    if (!buf || err != CL_SUCCESS) {
        pthread_mutex_unlock(&g_compile_mutex);
        return 0;
    }
    void* zeros = calloc(1, size_bytes);
    if (!zeros) {
        fn_clReleaseMemObject(buf);
        pthread_mutex_unlock(&g_compile_mutex);
        return 0;
    }
    pthread_mutex_lock(&dev->compute_mutex);
    err = fn_clEnqueueWriteBuffer(dev->compute_queue, buf, 1, 0, size_bytes, zeros, 0, NULL, NULL);
    pthread_mutex_unlock(&dev->compute_mutex);
    free(zeros);
    if (err != CL_SUCCESS) {
        fn_clReleaseMemObject(buf);
        pthread_mutex_unlock(&g_compile_mutex);
        return 0;
    }
    GoldLeafComputeVramBuffer* handle = (GoldLeafComputeVramBuffer*)calloc(1, sizeof(GoldLeafComputeVramBuffer));
    if (!handle) {
        fn_clReleaseMemObject(buf);
        pthread_mutex_unlock(&g_compile_mutex);
        return 0;
    }
    handle->device_index = (int)deviceIndex;
    handle->buf = buf;
    handle->size_bytes = size_bytes;
    pthread_mutex_unlock(&g_compile_mutex);
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT void JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeReleaseComputeVramBuffer(
    JNIEnv* env,
    jclass cls,
    jlong jBufHandle
) {
    (void)env;
    (void)cls;
    GoldLeafComputeVramBuffer* handle = (GoldLeafComputeVramBuffer*)(uintptr_t)jBufHandle;
    if (!handle) {
        return;
    }
    pthread_mutex_lock(&g_compile_mutex);
    if (handle->device_index >= 0 && handle->device_index < g_device_count) {
        GoldLeafDeviceState* dev = &g_devices[handle->device_index];
        if (dev->compute_mutex_initialized) {
            pthread_mutex_lock(&dev->compute_mutex);
            if (dev->compute_queue) {
                fn_clFinish(dev->compute_queue);
            }
            if (handle->buf) {
                fn_clReleaseMemObject(handle->buf);
                handle->buf = NULL;
            }
            pthread_mutex_unlock(&dev->compute_mutex);
        }
    }
    if (handle->buf) {
        fn_clReleaseMemObject(handle->buf);
        handle->buf = NULL;
    }
    free(handle);
    pthread_mutex_unlock(&g_compile_mutex);
}

JNIEXPORT jint JNICALL Java_dev_leafcraft_goldleaf_worldgen_GoldLeafOpenClBridge_nativeDispatchComputeKernel(
    JNIEnv* env,
    jclass cls,
    jlong jHandle,
    jobject jInputDirectBuf,
    jint jInputBytes,
    jlong jPersistentVramHandle,
    jobject jOutputDirectBuf,
    jint jOutputBytes,
    jintArray jScalarParams,
    jint jGlobalWorkItems,
    jint jLocalWorkItems,
    jboolean jClearOutputBeforeDispatch,
    jlongArray jOutTimingsNanos
) {
    (void)cls;
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || !jOutputDirectBuf) {
        return -1;
    }
    if (handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -2;
    }
    if (jInputBytes < 0 || jOutputBytes <= 0 || jGlobalWorkItems <= 0) {
        return -3;
    }
    if (jInputBytes > 0 && !jInputDirectBuf) {
        return -3;
    }

    const void* in_ptr = NULL;
    if (jInputBytes > 0) {
        in_ptr = (*env)->GetDirectBufferAddress(env, jInputDirectBuf);
        jlong in_cap = (*env)->GetDirectBufferCapacity(env, jInputDirectBuf);
        if (!in_ptr || in_cap < (jlong)jInputBytes) {
            return -4;
        }
    }
    void* out_ptr = (*env)->GetDirectBufferAddress(env, jOutputDirectBuf);
    jlong out_cap = (*env)->GetDirectBufferCapacity(env, jOutputDirectBuf);
    if (!out_ptr || out_cap < (jlong)jOutputBytes) {
        return -4;
    }

    jsize param_count = jScalarParams ? (*env)->GetArrayLength(env, jScalarParams) : 0;
    int32_t params_stack[64];
    int32_t* params_buf = params_stack;
    if (param_count > 64) {
        params_buf = (int32_t*)malloc((size_t)param_count * sizeof(int32_t));
        if (!params_buf) {
            return -7;
        }
    }
    if (param_count > 0) {
        (*env)->GetIntArrayRegion(env, jScalarParams, 0, param_count, (jint*)params_buf);
    }

    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    GoldLeafComputeVramBuffer* vram = (GoldLeafComputeVramBuffer*)(uintptr_t)jPersistentVramHandle;
    if (vram && (vram->device_index != handle->device_index || !vram->buf)) {
        if (params_buf != params_stack) free(params_buf);
        return -5;
    }

    pthread_mutex_lock(&dev->compute_mutex);

    cl_int err = CL_SUCCESS;
    size_t in_bytes = (size_t)jInputBytes;
    size_t out_bytes = (size_t)jOutputBytes;
    size_t params_bytes = param_count > 0 ? (size_t)param_count * sizeof(int32_t) : sizeof(int32_t);

    if (in_bytes > 0) {
        err = ensure_compute_buffer_locked(dev, &dev->compute_in_buf, &dev->compute_in_cap_bytes, CL_MEM_READ_ONLY, in_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_compute_buffer_locked(dev, &dev->compute_out_buf, &dev->compute_out_cap_bytes, CL_MEM_READ_WRITE, out_bytes);
    }
    if (err == CL_SUCCESS) {
        err = ensure_compute_buffer_locked(dev, &dev->compute_params_buf, &dev->compute_params_cap_bytes, CL_MEM_READ_ONLY, params_bytes);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->compute_mutex);
        if (params_buf != params_stack) free(params_buf);
        return (jint)err;
    }

    if (in_bytes > 0) {
        err = fn_clEnqueueWriteBuffer(dev->compute_queue, dev->compute_in_buf, 0, 0, in_bytes, in_ptr, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        if (param_count > 0) {
            err = fn_clEnqueueWriteBuffer(dev->compute_queue, dev->compute_params_buf, 1, 0, params_bytes, params_buf, 0, NULL, NULL);
        } else {
            int32_t zero_param = 0;
            err = fn_clEnqueueWriteBuffer(dev->compute_queue, dev->compute_params_buf, 1, 0, sizeof(int32_t), &zero_param, 0, NULL, NULL);
        }
    }
    if (err == CL_SUCCESS && jClearOutputBeforeDispatch == JNI_TRUE) {
        memset(out_ptr, 0, out_bytes);
        err = fn_clEnqueueWriteBuffer(dev->compute_queue, dev->compute_out_buf, 0, 0, out_bytes, out_ptr, 0, NULL, NULL);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->compute_mutex);
        if (params_buf != params_stack) free(params_buf);
        return (jint)err;
    }

    cl_mem arg_in = in_bytes > 0 ? dev->compute_in_buf : dev->compute_dummy_rw_buf;
    cl_mem arg_state = vram ? vram->buf : dev->compute_dummy_rw_buf;
    cl_mem arg_out = dev->compute_out_buf;
    cl_mem arg_params = dev->compute_params_buf;
    int32_t arg_param_count = (int32_t)param_count;
    int32_t arg_global_items = (int32_t)jGlobalWorkItems;

    size_t local_ws = dev->preferred_local_size;
    if (jLocalWorkItems > 0) {
        local_ws = (size_t)jLocalWorkItems;
        if (local_ws > dev->max_work_group_size) {
            local_ws = dev->max_work_group_size;
        }
    }
    if (local_ws == 0) {
        local_ws = 1;
    }
    size_t global_ws = (((size_t)jGlobalWorkItems + local_ws - 1) / local_ws) * local_ws;

    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();
    pthread_mutex_lock(&handle->kernel_arg_mutex);
    err = fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &arg_in);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &arg_state);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &arg_out);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(cl_mem), &arg_params);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_param_count);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_global_items);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueNDRangeKernel(dev->compute_queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    }
    pthread_mutex_unlock(&handle->kernel_arg_mutex);

    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&dev->compute_mutex);
        if (params_buf != params_stack) free(params_buf);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    err = fn_clEnqueueReadBuffer(dev->compute_queue, dev->compute_out_buf, 1, 0, out_bytes, out_ptr, 0, NULL, NULL);
    uint64_t t_read1 = monotonic_ns();

    uint64_t gpu_kern_ns = t_read1 >= t_kern0 ? (t_read1 - t_kern0) : 0;
    if (kern_ev) {
        cl_ulong ev_start = 0;
        cl_ulong ev_end = 0;
        if (fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &ev_start, NULL) == CL_SUCCESS
            && fn_clGetEventProfilingInfo(kern_ev, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &ev_end, NULL) == CL_SUCCESS
            && ev_end >= ev_start) {
            gpu_kern_ns = (uint64_t)(ev_end - ev_start);
        }
        fn_clReleaseEvent(kern_ev);
    }
    uint64_t readback_ns = t_read1 >= t_read0 ? (t_read1 - t_read0) : 0;

    pthread_mutex_unlock(&dev->compute_mutex);
    if (params_buf != params_stack) {
        free(params_buf);
    }

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}
