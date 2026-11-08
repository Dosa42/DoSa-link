#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct {
    char *home;
    char *classpath;
    char *request;
    char *output;
    char *progress;
    char **options;
    int option_count;
    char *result;
    char *error;
    FILE *log;
} WorkshopInvocation;

static char *joined(const char *first, const char *second) {
    size_t size = strlen(first) + strlen(second) + 1;
    char *value = malloc(size);
    if (value) snprintf(value, size, "%s%s", first, second);
    return value;
}

static int load_library(WorkshopInvocation *call, const char *relative) {
    if (call->log) { fprintf(call->log, "pid=%ld loading=%s\n", (long) getpid(), relative); fflush(call->log); }
    char *path = joined(call->home, relative);
    void *handle = path ? dlopen(path, RTLD_NOW | RTLD_GLOBAL) : NULL;
    if (!handle) {
        const char *detail = dlerror();
        size_t size = strlen(relative) + (detail ? strlen(detail) : 0) + 80;
        call->error = malloc(size);
        if (call->error) snprintf(call->error, size, "Unable to load %s: %s", relative, detail ? detail : "allocation failed");
    }
    free(path);
    return handle != NULL;
}

static void *invoke_vm(void *argument) {
    WorkshopInvocation *call = argument;
    if (!load_library(call, "/lib/server/libjvm.so")) return NULL;
    const char *libraries[] = {"/lib/libverify.so", "/lib/libjava.so", "/lib/libjimage.so", "/lib/libnet.so", "/lib/libnio.so", "/lib/libzip.so"};
    for (size_t index = 0; index < sizeof(libraries) / sizeof(libraries[0]); index++) {
        if (!load_library(call, libraries[index])) return NULL;
    }
    char *jvm_path = joined(call->home, "/lib/server/libjvm.so");
    void *jvm = dlopen(jvm_path, RTLD_NOW | RTLD_GLOBAL);
    free(jvm_path);
    jint (*create)(JavaVM **, void **, void *) = jvm ? dlsym(jvm, "JNI_CreateJavaVM") : NULL;
    if (!create) {
        call->error = strdup("Bundled libjvm does not export JNI_CreateJavaVM");
        return NULL;
    }
    int base_count = 8;
    JavaVMOption *options = calloc((size_t) (base_count + call->option_count), sizeof(JavaVMOption));
    if (!options) { call->error = strdup("Unable to allocate JVM option records"); return NULL; }
    options[0].optionString = joined("-Djava.home=", call->home);
    options[1].optionString = joined("-Djava.class.path=", call->classpath);
    char *library_path = joined(call->home, "/lib");
    options[2].optionString = joined("-Djava.library.path=", library_path);
    free(library_path);
    options[3].optionString = strdup("-Djava.awt.headless=true");
    options[4].optionString = joined("-Djava.io.tmpdir=", call->output);
    options[5].optionString = joined("-Duser.home=", call->output);
    char *error_path = joined(call->output, "/hs_err_pid%p.log");
    options[6].optionString = joined("-XX:ErrorFile=", error_path);
    options[7].optionString = strdup("-Dworkshop.jni=true");
    free(error_path);
    for (int index = 0; index < call->option_count; index++) options[base_count + index].optionString = call->options[index];
    JavaVMInitArgs config = {.version = JNI_VERSION_1_6, .nOptions = base_count + call->option_count, .options = options, .ignoreUnrecognized = JNI_FALSE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    if (call->log) { fprintf(call->log, "pid=%ld creating=HotSpot\n", (long) getpid()); fflush(call->log); }
    jint status = create(&vm, (void **) &env, &config);
    if (call->log) { fprintf(call->log, "pid=%ld create_status=%d\n", (long) getpid(), status); fflush(call->log); }
    for (int index = 0; index < base_count; index++) free((void *) options[index].optionString);
    free(options);
    if (status != JNI_OK || !env) {
        char message[96];
        snprintf(message, sizeof(message), "Bundled JNI_CreateJavaVM failed with status %d", status);
        call->error = strdup(message);
        return NULL;
    }
    jclass runner = (*env)->FindClass(env, "com/ayrvokan/jadxengine/Runner");
    jmethodID run = runner ? (*env)->GetStaticMethodID(env, runner, "run", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") : NULL;
    if (run) {
        jstring request = (*env)->NewStringUTF(env, call->request);
        jstring output = (*env)->NewStringUTF(env, call->output);
        jstring progress = (*env)->NewStringUTF(env, call->progress);
        jstring result = (jstring) (*env)->CallStaticObjectMethod(env, runner, run, request, output, progress);
        if (result && !(*env)->ExceptionCheck(env)) {
            const char *text = (*env)->GetStringUTFChars(env, result, NULL);
            if (text) { call->result = strdup(text); (*env)->ReleaseStringUTFChars(env, result, text); }
        }
    }
    if ((*env)->ExceptionCheck(env)) {
        jthrowable error = (*env)->ExceptionOccurred(env);
        (*env)->ExceptionClear(env);
        jclass throwable = (*env)->FindClass(env, "java/lang/Throwable");
        jmethodID describe = throwable ? (*env)->GetMethodID(env, throwable, "toString", "()Ljava/lang/String;") : NULL;
        jstring message = describe ? (jstring) (*env)->CallObjectMethod(env, error, describe) : NULL;
        if (message && !(*env)->ExceptionCheck(env)) {
            const char *text = (*env)->GetStringUTFChars(env, message, NULL);
            if (text) { call->error = strdup(text); (*env)->ReleaseStringUTFChars(env, message, text); }
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (!call->result && !call->error) call->error = strdup("Bundled JVM returned no result");
    (*vm)->DestroyJavaVM(vm);
    if (call->log) { fprintf(call->log, "pid=%ld vm_destroyed=true\n", (long) getpid()); fflush(call->log); }
    return NULL;
}

static char *copy_string(JNIEnv *env, jstring value) {
    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    char *copy = text ? strdup(text) : NULL;
    if (text) (*env)->ReleaseStringUTFChars(env, value, text);
    return copy;
}

JNIEXPORT jstring JNICALL Java_com_ayrvokan_wireless_NativeJadx_invoke(JNIEnv *env, jclass owner, jstring home, jstring classpath, jstring request, jstring output, jstring progress, jobjectArray vm_options) {
    (void) owner;
    WorkshopInvocation call = {0};
    call.home = copy_string(env, home);
    call.classpath = copy_string(env, classpath);
    call.request = copy_string(env, request);
    call.output = copy_string(env, output);
    call.progress = copy_string(env, progress);
    char *log_path = joined(call.output, "/native-jvm.log");
    if (log_path) { call.log = fopen(log_path, "a"); free(log_path); }
    call.option_count = (*env)->GetArrayLength(env, vm_options);
    call.options = calloc((size_t) call.option_count + 1, sizeof(char *));
    for (int index = 0; index < call.option_count; index++) {
        jstring value = (*env)->GetObjectArrayElement(env, vm_options, index);
        call.options[index] = copy_string(env, value);
        (*env)->DeleteLocalRef(env, value);
    }
    pthread_t worker;
    int status = pthread_create(&worker, NULL, invoke_vm, &call);
    if (status == 0) pthread_join(worker, NULL);
    else call.error = strdup("Unable to start the bundled JVM thread");
    jstring result = call.result ? (*env)->NewStringUTF(env, call.result) : NULL;
    if (!result) {
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, call.error ? call.error : "Native JVM invocation failed");
    }
    free(call.home); free(call.classpath); free(call.request); free(call.output); free(call.progress);
    for (int index = 0; index < call.option_count; index++) free(call.options[index]);
    free(call.options); free(call.result); free(call.error);
    if (call.log) fclose(call.log);
    return result;
}
