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
 *       Level 2: g_devices[d].queue_mutex (per-device queue & persistent buffers; never nested)
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
typedef void* (*pfn_clEnqueueMapBuffer)(cl_command_queue, cl_mem, cl_uint, cl_bitfield, size_t, size_t, cl_uint, const cl_event*, cl_event*, cl_int*);
typedef cl_int (*pfn_clEnqueueUnmapMemObject)(cl_command_queue, cl_mem, void*, cl_uint, const cl_event*, cl_event*);
typedef cl_int (*pfn_clFinish)(cl_command_queue);
typedef cl_int (*pfn_clWaitForEvents)(cl_uint, const cl_event*);
typedef cl_int (*pfn_clGetEventProfilingInfo)(cl_event, cl_uint, size_t, void*, size_t*);
typedef cl_int (*pfn_clReleaseEvent)(cl_event);
typedef cl_int (*pfn_clReleaseMemObject)(cl_mem);
typedef cl_int (*pfn_clReleaseKernel)(cl_kernel);
typedef cl_int (*pfn_clReleaseProgram)(cl_program);

typedef struct {
    cl_platform_id platform;
    cl_device_id device;
    cl_context context;
    cl_command_queue queue;
    char name[192];
    char vendor[128];
    char driver_version[128];
    int supports_fp64;
    size_t max_work_group_size;
    size_t preferred_local_size;
    pthread_mutex_t queue_mutex;

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
    cl_mem dummy_spill_buf;
} GoldLeafDeviceState;

typedef struct {
    int device_index;
    cl_program program;
    cl_kernel kernel;
    cl_mem spill_buf;
} GoldLeafProgramHandle;

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
static pfn_clEnqueueMapBuffer fn_clEnqueueMapBuffer = NULL;
static pfn_clEnqueueUnmapMemObject fn_clEnqueueUnmapMemObject = NULL;
static pfn_clFinish fn_clFinish = NULL;
static pfn_clWaitForEvents fn_clWaitForEvents = NULL;
static pfn_clGetEventProfilingInfo fn_clGetEventProfilingInfo = NULL;
static pfn_clReleaseEvent fn_clReleaseEvent = NULL;
static pfn_clReleaseMemObject fn_clReleaseMemObject = NULL;
static pfn_clReleaseKernel fn_clReleaseKernel = NULL;
static pfn_clReleaseProgram fn_clReleaseProgram = NULL;

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
    LOAD_SYM(clEnqueueMapBuffer);
    LOAD_SYM(clEnqueueUnmapMemObject);
    LOAD_SYM(clFinish);
    LOAD_SYM(clWaitForEvents);
    LOAD_SYM(clGetEventProfilingInfo);
    LOAD_SYM(clReleaseEvent);
    LOAD_SYM(clReleaseMemObject);
    LOAD_SYM(clReleaseKernel);
    LOAD_SYM(clReleaseProgram);
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
            st.queue = fn_clCreateCommandQueue(st.context, devs[d], CL_QUEUE_PROFILING_ENABLE, &err);
            if (!st.queue || err != CL_SUCCESS) {
                st.queue = fn_clCreateCommandQueue(st.context, devs[d], 0, &err);
            }
            if (!st.queue || err != CL_SUCCESS) {
                continue;
            }
            st.dummy_spill_buf = fn_clCreateBuffer(st.context, CL_MEM_READ_ONLY, sizeof(int32_t), NULL, &err);
            if (!st.dummy_spill_buf || err != CL_SUCCESS) {
                continue;
            }
            pthread_mutex_init(&st.queue_mutex, NULL);
            g_devices[g_device_count++] = st;
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
                    handle->spill_buf = NULL;
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
                    handle->spill_buf = NULL;
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
    pthread_mutex_lock(&dev->queue_mutex);
    if (handle->spill_buf) {
        fn_clReleaseMemObject(handle->spill_buf);
        handle->spill_buf = NULL;
    }
    cl_int err = CL_SUCCESS;
    size_t bytes = (size_t)len * sizeof(int32_t);
    cl_mem buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
    if (buf && err == CL_SUCCESS) {
        err = fn_clEnqueueWriteBuffer(dev->queue, buf, 1, 0, bytes, words, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            handle->spill_buf = buf;
        } else {
            fn_clReleaseMemObject(buf);
        }
    }
    pthread_mutex_unlock(&dev->queue_mutex);
    pthread_mutex_unlock(&g_compile_mutex);
    (*env)->ReleaseIntArrayElements(env, jSpillWords, words, JNI_ABORT);
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
    GoldLeafProgramHandle* handle = (GoldLeafProgramHandle*)(uintptr_t)jHandle;
    if (!handle || !handle->kernel || handle->device_index < 0 || handle->device_index >= g_device_count) {
        return -1;
    }
    if (batchCount <= 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !jOriginsXyz4 || !jOutValues) {
        return -2;
    }
    size_t volume_size = (size_t)sizeX * (size_t)sizeY * (size_t)sizeZ;
    size_t total_floats = (size_t)batchCount * volume_size;
    if ((size_t)(*env)->GetArrayLength(env, jOriginsXyz4) < (size_t)batchCount * 4
        || (size_t)(*env)->GetArrayLength(env, jOutValues) < total_floats) {
        return -3;
    }

    GoldLeafDeviceState* dev = &g_devices[handle->device_index];
    pthread_mutex_lock(&dev->queue_mutex);

    cl_int err = CL_SUCCESS;
    if (dev->origins_cap_chunks < (size_t)batchCount) {
        if (dev->origins_host_buf) {
            if (dev->origins_host_ptr) {
                fn_clEnqueueUnmapMemObject(dev->queue, dev->origins_host_buf, dev->origins_host_ptr, 0, NULL, NULL);
                fn_clFinish(dev->queue);
                dev->origins_host_ptr = NULL;
            }
            fn_clReleaseMemObject(dev->origins_host_buf);
            dev->origins_host_buf = NULL;
        }
        if (dev->origins_buf) {
            fn_clReleaseMemObject(dev->origins_buf);
            dev->origins_buf = NULL;
        }
        size_t new_cap = (size_t)batchCount < 64 ? 64 : (size_t)batchCount;
        size_t bytes = new_cap * 4 * sizeof(int32_t);
        dev->origins_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
        if (!dev->origins_buf || err != CL_SUCCESS) {
            dev->origins_cap_chunks = 0;
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -5);
        }
        cl_int map_err = CL_SUCCESS;
        dev->origins_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, NULL, &map_err);
        if (dev->origins_host_buf && map_err == CL_SUCCESS) {
            dev->origins_host_ptr = (int32_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->origins_host_buf, 1, CL_MAP_WRITE, 0, bytes, 0, NULL, NULL, &map_err);
            if (!dev->origins_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->origins_host_buf);
                dev->origins_host_buf = NULL;
                dev->origins_host_ptr = NULL;
            }
        } else {
            dev->origins_host_buf = NULL;
            dev->origins_host_ptr = NULL;
        }
        dev->origins_cap_chunks = new_cap;
    }

    if (dev->out_values_cap_floats < total_floats) {
        if (dev->out_host_buf) {
            if (dev->out_host_ptr) {
                fn_clEnqueueUnmapMemObject(dev->queue, dev->out_host_buf, dev->out_host_ptr, 0, NULL, NULL);
                fn_clFinish(dev->queue);
                dev->out_host_ptr = NULL;
            }
            fn_clReleaseMemObject(dev->out_host_buf);
            dev->out_host_buf = NULL;
        }
        if (dev->out_values_buf) {
            fn_clReleaseMemObject(dev->out_values_buf);
            dev->out_values_buf = NULL;
        }
        size_t out_bytes = total_floats * sizeof(float);
        dev->out_values_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, out_bytes, NULL, &err);
        if (!dev->out_values_buf || err != CL_SUCCESS) {
            dev->out_values_cap_floats = 0;
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -6);
        }
        cl_int map_err = CL_SUCCESS;
        dev->out_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, out_bytes, NULL, &map_err);
        if (dev->out_host_buf && map_err == CL_SUCCESS) {
            dev->out_host_ptr = (float*)fn_clEnqueueMapBuffer(
                dev->queue, dev->out_host_buf, 1, CL_MAP_READ, 0, out_bytes, 0, NULL, NULL, &map_err);
            if (!dev->out_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->out_host_buf);
                dev->out_host_buf = NULL;
                dev->out_host_ptr = NULL;
            }
        } else {
            dev->out_host_buf = NULL;
            dev->out_host_ptr = NULL;
        }
        dev->out_values_cap_floats = total_floats;
    }

    if (dev->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)dev->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), dev->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&dev->queue_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
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

    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &dev->out_values_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &dev->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_sx);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sy);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sz);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_stx);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_sty);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(int32_t), &arg_stz);
    err |= fn_clSetKernelArg(handle->kernel, 9, sizeof(cl_mem), &spill);

    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    size_t local_ws = dev->preferred_local_size > 0 ? dev->preferred_local_size : 128;
    size_t global_ws = ((total_floats + local_ws - 1) / local_ws) * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();
    err = fn_clEnqueueNDRangeKernel(dev->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    if (dev->out_host_ptr) {
        err = fn_clEnqueueReadBuffer(dev->queue, dev->out_values_buf, 1, 0,
            total_floats * sizeof(float), dev->out_host_ptr, 0, NULL, NULL);
        if (err == CL_SUCCESS) {
            (*env)->SetFloatArrayRegion(env, jOutValues, 0, (jsize)total_floats, dev->out_host_ptr);
        }
    } else {
        jfloat* out_ptr = (*env)->GetFloatArrayElements(env, jOutValues, NULL);
        if (!out_ptr) {
            if (kern_ev) fn_clReleaseEvent(kern_ev);
            pthread_mutex_unlock(&dev->queue_mutex);
            return -7;
        }
        err = fn_clEnqueueReadBuffer(dev->queue, dev->out_values_buf, 1, 0,
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

    pthread_mutex_unlock(&dev->queue_mutex);

    if (jOutTimingsNanos && (*env)->GetArrayLength(env, jOutTimingsNanos) >= 2) {
        jlong timings[2] = { (jlong)gpu_kern_ns, (jlong)readback_ns };
        (*env)->SetLongArrayRegion(env, jOutTimingsNanos, 0, 2, timings);
    }
    return (jint)err;
}

static void release_compact_buffers_locked(GoldLeafDeviceState* dev) {
    if (dev->compact_words_host_buf) {
        if (dev->compact_words_host_ptr) {
            fn_clEnqueueUnmapMemObject(dev->queue, dev->compact_words_host_buf, dev->compact_words_host_ptr, 0, NULL, NULL);
            dev->compact_words_host_ptr = NULL;
        }
        fn_clReleaseMemObject(dev->compact_words_host_buf);
        dev->compact_words_host_buf = NULL;
    }
    if (dev->compact_meta_host_buf) {
        if (dev->compact_meta_host_ptr) {
            fn_clEnqueueUnmapMemObject(dev->queue, dev->compact_meta_host_buf, dev->compact_meta_host_ptr, 0, NULL, NULL);
            dev->compact_meta_host_ptr = NULL;
        }
        fn_clReleaseMemObject(dev->compact_meta_host_buf);
        dev->compact_meta_host_buf = NULL;
    }
    if (dev->compact_words_buf) {
        fn_clReleaseMemObject(dev->compact_words_buf);
        dev->compact_words_buf = NULL;
    }
    if (dev->compact_meta_buf) {
        fn_clReleaseMemObject(dev->compact_meta_buf);
        dev->compact_meta_buf = NULL;
    }
    fn_clFinish(dev->queue);
    dev->compact_cap_sections = 0;
    dev->compact_cap_word_bytes = 0;
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

    pthread_mutex_lock(&dev->queue_mutex);

    cl_int err = CL_SUCCESS;
    if (dev->origins_cap_chunks < (size_t)batchCount) {
        if (dev->origins_host_buf) {
            if (dev->origins_host_ptr) {
                fn_clEnqueueUnmapMemObject(dev->queue, dev->origins_host_buf, dev->origins_host_ptr, 0, NULL, NULL);
                fn_clFinish(dev->queue);
                dev->origins_host_ptr = NULL;
            }
            fn_clReleaseMemObject(dev->origins_host_buf);
            dev->origins_host_buf = NULL;
        }
        if (dev->origins_buf) {
            fn_clReleaseMemObject(dev->origins_buf);
            dev->origins_buf = NULL;
        }
        size_t new_cap = (size_t)batchCount < 64 ? 64 : (size_t)batchCount;
        size_t bytes = new_cap * 4 * sizeof(int32_t);
        dev->origins_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
        if (!dev->origins_buf || err != CL_SUCCESS) {
            dev->origins_cap_chunks = 0;
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -5);
        }
        cl_int map_err = CL_SUCCESS;
        dev->origins_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, NULL, &map_err);
        if (dev->origins_host_buf && map_err == CL_SUCCESS) {
            dev->origins_host_ptr = (int32_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->origins_host_buf, 1, CL_MAP_WRITE, 0, bytes, 0, NULL, NULL, &map_err);
            if (!dev->origins_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->origins_host_buf);
                dev->origins_host_buf = NULL;
                dev->origins_host_ptr = NULL;
            }
        } else {
            dev->origins_host_buf = NULL;
            dev->origins_host_ptr = NULL;
        }
        dev->origins_cap_chunks = new_cap;
    }

    if (dev->compact_cap_sections < total_sections || dev->compact_cap_word_bytes < words_bytes) {
        release_compact_buffers_locked(dev);
        size_t new_sec_cap = total_sections < 768 ? 768 : total_sections;
        size_t alloc_words_bytes = new_sec_cap * 256 * sizeof(uint32_t);
        if (alloc_words_bytes < words_bytes) {
            alloc_words_bytes = words_bytes;
        }
        size_t alloc_meta_bytes = new_sec_cap * 4 * sizeof(uint16_t);

        dev->compact_words_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_words_bytes, NULL, &err);
        if (!dev->compact_words_buf || err != CL_SUCCESS) {
            release_compact_buffers_locked(dev);
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -6);
        }
        dev->compact_meta_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_meta_bytes, NULL, &err);
        if (!dev->compact_meta_buf || err != CL_SUCCESS) {
            release_compact_buffers_locked(dev);
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -6);
        }

        cl_int map_err = CL_SUCCESS;
        dev->compact_words_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_words_bytes, NULL, &map_err);
        if (dev->compact_words_host_buf && map_err == CL_SUCCESS) {
            dev->compact_words_host_ptr = (uint32_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->compact_words_host_buf, 1, CL_MAP_READ, 0, alloc_words_bytes, 0, NULL, NULL, &map_err);
            if (!dev->compact_words_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->compact_words_host_buf);
                dev->compact_words_host_buf = NULL;
                dev->compact_words_host_ptr = NULL;
            }
        }
        map_err = CL_SUCCESS;
        dev->compact_meta_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_meta_bytes, NULL, &map_err);
        if (dev->compact_meta_host_buf && map_err == CL_SUCCESS) {
            dev->compact_meta_host_ptr = (uint16_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->compact_meta_host_buf, 1, CL_MAP_READ, 0, alloc_meta_bytes, 0, NULL, NULL, &map_err);
            if (!dev->compact_meta_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->compact_meta_host_buf);
                dev->compact_meta_host_buf = NULL;
                dev->compact_meta_host_ptr = NULL;
            }
        }
        dev->compact_cap_sections = new_sec_cap;
        dev->compact_cap_word_bytes = alloc_words_bytes;
    }

    if (dev->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)dev->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), dev->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&dev->queue_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sections = (int32_t)sectionCount;
    int32_t arg_sea = (int32_t)seaLevel;
    int32_t arg_deep = (int32_t)deepFluidY;

    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &dev->compact_words_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &dev->compact_meta_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &dev->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sea);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_deep);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(cl_mem), &spill);

    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    size_t local_ws = 256;
    size_t global_ws = total_sections * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();
    err = fn_clEnqueueNDRangeKernel(dev->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    void* words_dst = dev->compact_words_host_ptr ? (void*)dev->compact_words_host_ptr : out_words_ptr;
    void* meta_dst = dev->compact_meta_host_ptr ? (void*)dev->compact_meta_host_ptr : out_meta_ptr;
    err = fn_clEnqueueReadBuffer(dev->queue, dev->compact_words_buf, 0, 0, words_bytes, words_dst, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(dev->queue, dev->compact_meta_buf, 1, 0, meta_bytes, meta_dst, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        if (dev->compact_words_host_ptr) {
            memcpy(out_words_ptr, dev->compact_words_host_ptr, words_bytes);
        }
        if (dev->compact_meta_host_ptr) {
            memcpy(out_meta_ptr, dev->compact_meta_host_ptr, meta_bytes);
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

    pthread_mutex_unlock(&dev->queue_mutex);

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

    pthread_mutex_lock(&dev->queue_mutex);

    cl_int err = CL_SUCCESS;
    if (dev->origins_cap_chunks < (size_t)batchCount) {
        if (dev->origins_host_buf) {
            if (dev->origins_host_ptr) {
                fn_clEnqueueUnmapMemObject(dev->queue, dev->origins_host_buf, dev->origins_host_ptr, 0, NULL, NULL);
                fn_clFinish(dev->queue);
                dev->origins_host_ptr = NULL;
            }
            fn_clReleaseMemObject(dev->origins_host_buf);
            dev->origins_host_buf = NULL;
        }
        if (dev->origins_buf) {
            fn_clReleaseMemObject(dev->origins_buf);
            dev->origins_buf = NULL;
        }
        size_t new_cap = (size_t)batchCount < 64 ? 64 : (size_t)batchCount;
        size_t bytes = new_cap * 4 * sizeof(int32_t);
        dev->origins_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY, bytes, NULL, &err);
        if (!dev->origins_buf || err != CL_SUCCESS) {
            dev->origins_cap_chunks = 0;
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -5);
        }
        cl_int map_err = CL_SUCCESS;
        dev->origins_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, NULL, &map_err);
        if (dev->origins_host_buf && map_err == CL_SUCCESS) {
            dev->origins_host_ptr = (int32_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->origins_host_buf, 1, CL_MAP_WRITE, 0, bytes, 0, NULL, NULL, &map_err);
            if (!dev->origins_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->origins_host_buf);
                dev->origins_host_buf = NULL;
                dev->origins_host_ptr = NULL;
            }
        } else {
            dev->origins_host_buf = NULL;
            dev->origins_host_ptr = NULL;
        }
        dev->origins_cap_chunks = new_cap;
    }

    if (dev->compact_cap_sections < total_sections || dev->compact_cap_word_bytes < words_bytes) {
        release_compact_buffers_locked(dev);
        size_t new_sec_cap = total_sections < 768 ? 768 : total_sections;
        size_t alloc_words_bytes = new_sec_cap * 256 * sizeof(uint64_t);
        if (alloc_words_bytes < words_bytes) {
            alloc_words_bytes = words_bytes;
        }
        size_t alloc_meta_bytes = new_sec_cap * 4 * sizeof(uint16_t);

        dev->compact_words_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_words_bytes, NULL, &err);
        if (!dev->compact_words_buf || err != CL_SUCCESS) {
            release_compact_buffers_locked(dev);
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -6);
        }
        dev->compact_meta_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY, alloc_meta_bytes, NULL, &err);
        if (!dev->compact_meta_buf || err != CL_SUCCESS) {
            release_compact_buffers_locked(dev);
            pthread_mutex_unlock(&dev->queue_mutex);
            return (jint)(err != CL_SUCCESS ? err : -6);
        }

        cl_int map_err = CL_SUCCESS;
        dev->compact_words_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_words_bytes, NULL, &map_err);
        if (dev->compact_words_host_buf && map_err == CL_SUCCESS) {
            dev->compact_words_host_ptr = (uint32_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->compact_words_host_buf, 1, CL_MAP_READ, 0, alloc_words_bytes, 0, NULL, NULL, &map_err);
            if (!dev->compact_words_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->compact_words_host_buf);
                dev->compact_words_host_buf = NULL;
                dev->compact_words_host_ptr = NULL;
            }
        }
        map_err = CL_SUCCESS;
        dev->compact_meta_host_buf = fn_clCreateBuffer(dev->context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, alloc_meta_bytes, NULL, &map_err);
        if (dev->compact_meta_host_buf && map_err == CL_SUCCESS) {
            dev->compact_meta_host_ptr = (uint16_t*)fn_clEnqueueMapBuffer(
                dev->queue, dev->compact_meta_host_buf, 1, CL_MAP_READ, 0, alloc_meta_bytes, 0, NULL, NULL, &map_err);
            if (!dev->compact_meta_host_ptr || map_err != CL_SUCCESS) {
                fn_clReleaseMemObject(dev->compact_meta_host_buf);
                dev->compact_meta_host_buf = NULL;
                dev->compact_meta_host_ptr = NULL;
            }
        }
        dev->compact_cap_sections = new_sec_cap;
        dev->compact_cap_word_bytes = alloc_words_bytes;
    }

    if (dev->origins_host_ptr) {
        (*env)->GetIntArrayRegion(env, jOriginsXyz4, 0, batchCount * 4, (jint*)dev->origins_host_ptr);
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 0, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), dev->origins_host_ptr, 0, NULL, NULL);
    } else {
        jint* origins = (*env)->GetIntArrayElements(env, jOriginsXyz4, NULL);
        if (!origins) {
            pthread_mutex_unlock(&dev->queue_mutex);
            return -4;
        }
        err = fn_clEnqueueWriteBuffer(dev->queue, dev->origins_buf, 1, 0,
            (size_t)batchCount * 4 * sizeof(int32_t), origins, 0, NULL, NULL);
        (*env)->ReleaseIntArrayElements(env, jOriginsXyz4, origins, JNI_ABORT);
    }
    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    cl_mem spill = handle->spill_buf ? handle->spill_buf : dev->dummy_spill_buf;
    int32_t arg_batch = (int32_t)batchCount;
    int32_t arg_sections = (int32_t)sectionCount;
    int32_t arg_sea = (int32_t)seaLevel;
    int32_t arg_deep = (int32_t)deepFluidY;
    int32_t arg_min_y = (int32_t)minBlockY;

    err |= fn_clSetKernelArg(handle->kernel, 0, sizeof(cl_mem), &dev->compact_words_buf);
    err |= fn_clSetKernelArg(handle->kernel, 1, sizeof(cl_mem), &dev->compact_meta_buf);
    err |= fn_clSetKernelArg(handle->kernel, 2, sizeof(cl_mem), &dev->origins_buf);
    err |= fn_clSetKernelArg(handle->kernel, 3, sizeof(int32_t), &arg_batch);
    err |= fn_clSetKernelArg(handle->kernel, 4, sizeof(int32_t), &arg_sections);
    err |= fn_clSetKernelArg(handle->kernel, 5, sizeof(int32_t), &arg_sea);
    err |= fn_clSetKernelArg(handle->kernel, 6, sizeof(int32_t), &arg_deep);
    err |= fn_clSetKernelArg(handle->kernel, 7, sizeof(int32_t), &arg_min_y);
    err |= fn_clSetKernelArg(handle->kernel, 8, sizeof(cl_mem), &spill);

    if (err != CL_SUCCESS) {
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    size_t local_ws = 256;
    size_t global_ws = (size_t)batchCount * local_ws;
    cl_event kern_ev = NULL;
    uint64_t t_kern0 = monotonic_ns();
    err = fn_clEnqueueNDRangeKernel(dev->queue, handle->kernel, 1, NULL, &global_ws, &local_ws, 0, NULL, &kern_ev);
    if (err != CL_SUCCESS) {
        if (kern_ev) fn_clReleaseEvent(kern_ev);
        pthread_mutex_unlock(&dev->queue_mutex);
        return (jint)err;
    }

    uint64_t t_read0 = monotonic_ns();
    void* words_dst = dev->compact_words_host_ptr ? (void*)dev->compact_words_host_ptr : out_words_ptr;
    void* meta_dst = dev->compact_meta_host_ptr ? (void*)dev->compact_meta_host_ptr : out_meta_ptr;
    err = fn_clEnqueueReadBuffer(dev->queue, dev->compact_words_buf, 0, 0, words_bytes, words_dst, 0, NULL, NULL);
    if (err == CL_SUCCESS) {
        err = fn_clEnqueueReadBuffer(dev->queue, dev->compact_meta_buf, 1, 0, meta_bytes, meta_dst, 0, NULL, NULL);
    }
    if (err == CL_SUCCESS) {
        if (dev->compact_words_host_ptr) {
            memcpy(out_words_ptr, dev->compact_words_host_ptr, words_bytes);
        }
        if (dev->compact_meta_host_ptr) {
            memcpy(out_meta_ptr, dev->compact_meta_host_ptr, meta_bytes);
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

    pthread_mutex_unlock(&dev->queue_mutex);

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
        pthread_mutex_lock(&dev->queue_mutex);
        fn_clFinish(dev->queue);
        if (handle->spill_buf) {
            fn_clReleaseMemObject(handle->spill_buf);
            handle->spill_buf = NULL;
        }
        pthread_mutex_unlock(&dev->queue_mutex);
    }
    if (handle->kernel) {
        fn_clReleaseKernel(handle->kernel);
        handle->kernel = NULL;
    }
    if (handle->program) {
        fn_clReleaseProgram(handle->program);
        handle->program = NULL;
    }
    free(handle);
    pthread_mutex_unlock(&g_compile_mutex);
}
