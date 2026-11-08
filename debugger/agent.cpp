#include <jni.h>
#include <jvmti.h>
#include <unistd.h>
#include <signal.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <sstream>
#include <fstream>
#include <mutex>
#include <condition_variable>
#include <chrono>

static jvmtiEnv *ti = nullptr;
static std::mutex guard;
static std::condition_variable changed;
static std::string folder, state = "{\"phase\":\"unattached\"}", command;
static jmethodID armed = nullptr;
static jlocation armed_location = 0;
static jthread held = nullptr;
static bool enabled = false;
static int controller = 0, edit_depth = 0, edit_slot = 0, edit_value = 0;
static std::string controller_ticks;
static unsigned long sequence = 0;

static std::string quote(const std::string &value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 32) { char hex[7]; std::snprintf(hex, sizeof(hex), "\\u%04x", c); out += hex; }
        else out += c;
    }
    return out + "\"";
}

static std::string utf(JNIEnv *env, jstring value) {
    if (!value) return "";
    const char *text = env->GetStringUTFChars(value, nullptr);
    std::string result(text ? text : "");
    if (text) env->ReleaseStringUTFChars(value, text);
    return result;
}

static std::string hex(uintptr_t value) { std::ostringstream out; out << "0x" << std::hex << value; return out.str(); }
static void release(void *memory) { if (memory) ti->Deallocate(reinterpret_cast<unsigned char *>(memory)); }

static void publish(const std::string &phase, const std::string &body = "") {
    state = "{\"backend\":\"ART-JVMTI\",\"pid\":" + std::to_string(getpid()) + ",\"sequence\":" + std::to_string(++sequence) + ",\"phase\":" + quote(phase) + body + "}";
    if (folder.empty()) return;
    std::string part = folder + "/state.json.part";
    { std::ofstream output(part); output << state << '\n'; }
    std::rename(part.c_str(), (folder + "/state.json").c_str());
    std::ofstream events(folder + "/agent-events.jsonl", std::ios::app); events << state << '\n';
}

static std::string ticks(int pid) {
    std::ifstream input("/proc/" + std::to_string(pid) + "/stat"); std::string line; std::getline(input, line);
    auto end = line.rfind(')'); if (end == std::string::npos) return "";
    std::istringstream values(line.substr(end + 2)); std::string token;
    for (int field = 3; field <= 22; ++field) if (!(values >> token)) return "";
    return token;
}

static std::string frame(JNIEnv *env, jthread thread, jint depth, const jvmtiFrameInfo &value) {
    char *name = nullptr, *signature = nullptr, *generic = nullptr, *owner_name = nullptr, *source = nullptr;
    jclass owner = nullptr;
    ti->GetMethodName(value.method, &name, &signature, &generic);
    ti->GetMethodDeclaringClass(value.method, &owner);
    if (owner) { ti->GetClassSignature(owner, &owner_name, nullptr); ti->GetSourceFileName(owner, &source); }
    jint count = 0, line = -1; jvmtiLineNumberEntry *lines = nullptr;
    if (ti->GetLineNumberTable(value.method, &count, &lines) == JVMTI_ERROR_NONE) for (int i = 0; i < count; ++i) if (lines[i].start_location <= value.location) line = lines[i].line_number;
    std::string result = "{\"depth\":" + std::to_string(depth) + ",\"class\":" + quote(owner_name ? owner_name : "") + ",\"method\":" + quote(name ? name : "") + ",\"signature\":" + quote(signature ? signature : "") + ",\"methodId\":" + quote(hex(reinterpret_cast<uintptr_t>(value.method))) + ",\"locationKind\":\"ART DEX code-unit offset\",\"location\":" + std::to_string(value.location) + ",\"sourceFile\":" + quote(source ? source : "") + ",\"sourceLine\":" + std::to_string(line) + ",\"locals\":[";
    jint local_count = 0; jvmtiLocalVariableEntry *locals = nullptr;
    jvmtiError error = ti->GetLocalVariableTable(value.method, &local_count, &locals);
    bool first = true;
    if (error == JVMTI_ERROR_NONE) for (int i = 0; i < local_count; ++i) {
        auto &local = locals[i];
        if (value.location >= local.start_location && value.location < local.start_location + local.length && local.signature) {
            jvmtiError read_error = JVMTI_ERROR_NONE; std::string observed = "null"; char type = local.signature[0];
            if (std::strchr("ZBCSI", type)) { jint v = 0; read_error = ti->GetLocalInt(thread, depth, local.slot, &v); observed = std::to_string(v); }
            else if (type == 'J') { jlong v = 0; read_error = ti->GetLocalLong(thread, depth, local.slot, &v); observed = std::to_string(v); }
            else if (type == 'F') { jfloat v = 0; read_error = ti->GetLocalFloat(thread, depth, local.slot, &v); observed = quote(std::to_string(v)); }
            else if (type == 'D') { jdouble v = 0; read_error = ti->GetLocalDouble(thread, depth, local.slot, &v); observed = quote(std::to_string(v)); }
            else { jobject object = nullptr; read_error = ti->GetLocalObject(thread, depth, local.slot, &object); if (object) { observed = std::strcmp(local.signature, "Ljava/lang/String;") == 0 ? quote(utf(env, static_cast<jstring>(object))) : quote(std::string("object:") + local.signature); env->DeleteLocalRef(object); } }
            if (!first) result += ','; first = false;
            result += "{\"name\":" + quote(local.name ? local.name : "") + ",\"type\":" + quote(local.signature) + ",\"slot\":" + std::to_string(local.slot) + ",\"error\":" + std::to_string(read_error) + ",\"value\":" + (read_error == JVMTI_ERROR_NONE ? observed : "null") + "}";
        }
        release(local.name); release(local.signature); release(local.generic_signature);
    }
    result += "],\"localsError\":" + std::to_string(error) + "}";
    release(locals); release(lines); release(name); release(signature); release(generic); release(owner_name); release(source);
    if (owner) env->DeleteLocalRef(owner);
    return result;
}

static void inspect(JNIEnv *env, jthread thread, const std::string &phase, int edit_error = 0) {
    jvmtiFrameInfo frames[64]; jint count = 0; jvmtiError error = ti->GetStackTrace(thread, 0, 64, frames, &count);
    jvmtiThreadInfo info = {}; ti->GetThreadInfo(thread, &info);
    std::string body = ",\"thread\":" + quote(info.name ? info.name : "") + ",\"stackError\":" + std::to_string(error) + ",\"editError\":" + std::to_string(edit_error) + ",\"frames\":[";
    for (int i = 0; i < count; ++i) { if (i) body += ','; body += frame(env, thread, i, frames[i]); }
    body += ']'; release(info.name);
    if (info.thread_group) env->DeleteLocalRef(info.thread_group);
    if (info.context_class_loader) env->DeleteLocalRef(info.context_class_loader);
    publish(phase, body);
}

static void JNICALL stopped(jvmtiEnv *, JNIEnv *env, jthread thread, jmethodID, jlocation) {
    std::unique_lock<std::mutex> lock(guard);
    if (!enabled || held) return;
    held = static_cast<jthread>(env->NewGlobalRef(thread));
    ti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    inspect(env, thread, "paused");
    while (enabled) {
        changed.wait_for(lock, std::chrono::milliseconds(400), [] { return !command.empty() || !enabled; });
        if (controller > 0 && ticks(controller) != controller_ticks) {
            enabled = false;
            if (armed) ti->ClearBreakpoint(armed, armed_location);
            armed = nullptr;
            publish("controller-gone"); break;
        }
        std::string action = command; command.clear();
        if (action == "resume" || action == "step") {
            if (action == "step") ti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thread);
            publish("running"); break;
        }
        if (action == "inspect") inspect(env, thread, "paused");
        if (action == "set-int") { auto error = ti->SetLocalInt(thread, edit_depth, edit_slot, edit_value); inspect(env, thread, "paused", error); }
    }
    env->DeleteGlobalRef(held); held = nullptr;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *, void *) {
    if (ti) return JNI_OK;
    if (vm->GetEnv(reinterpret_cast<void **>(&ti), JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    jvmtiCapabilities capabilities = {};
    capabilities.can_generate_breakpoint_events = 1; capabilities.can_generate_single_step_events = 1;
    capabilities.can_access_local_variables = 1; capabilities.can_get_line_numbers = 1; capabilities.can_get_source_file_name = 1;
    if (ti->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) return JNI_ERR;
    jvmtiEventCallbacks callbacks = {}; callbacks.Breakpoint = stopped; callbacks.SingleStep = stopped;
    if (ti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_ERR;
    ti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr);
    return JNI_OK;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_ayrvokan_wireless_NativeDebug_arm(JNIEnv *env, jclass, jclass target, jstring method, jstring descriptor, jlong location, jint line, jstring output, jint pid, jstring start) {
    std::lock_guard<std::mutex> lock(guard);
    folder = utf(env, output); controller = pid; controller_ticks = utf(env, start);
    if (!ti) { publish("error", ",\"error\":\"JVMTI agent is not attached\""); return env->NewStringUTF(state.c_str()); }
    if (held) { publish("error", ",\"error\":\"Resume or detach the current breakpoint first\""); return env->NewStringUTF(state.c_str()); }
    std::string wanted = utf(env, method), desc = utf(env, descriptor); jint count = 0; jmethodID *methods = nullptr;
    jvmtiError error = ti->GetClassMethods(target, &count, &methods); jmethodID selected = nullptr;
    for (int i = 0; i < count; ++i) { char *name = nullptr, *signature = nullptr; ti->GetMethodName(methods[i], &name, &signature, nullptr); if (name && signature && wanted == name && desc == signature) selected = methods[i]; release(name); release(signature); }
    release(methods);
    if (armed) ti->ClearBreakpoint(armed, armed_location);
    armed = nullptr; enabled = false;
    if (!selected) { publish("error", ",\"error\":\"Method not found\",\"jvmtiError\":" + std::to_string(error)); return env->NewStringUTF(state.c_str()); }
    jlocation first = 0, last = 0; error = ti->GetMethodLocation(selected, &first, &last);
    jlocation chosen = location >= 0 ? location : first;
    if (line > 0) { jint n = 0; jvmtiLineNumberEntry *lines = nullptr; error = ti->GetLineNumberTable(selected, &n, &lines); bool found = false; for (int i = 0; i < n; ++i) if (lines[i].line_number == line) { chosen = lines[i].start_location; found = true; break; } release(lines); if (!found) error = JVMTI_ERROR_ABSENT_INFORMATION; }
    if (error == JVMTI_ERROR_NONE) error = ti->SetBreakpoint(selected, chosen);
    if (error == JVMTI_ERROR_NONE) { armed = selected; armed_location = chosen; enabled = true; command.clear(); }
    publish(error == JVMTI_ERROR_NONE ? "armed" : "error", ",\"method\":" + quote(wanted) + ",\"signature\":" + quote(desc) + ",\"locationKind\":\"ART DEX code-unit offset\",\"location\":" + std::to_string(chosen) + ",\"jvmtiError\":" + std::to_string(error));
    return env->NewStringUTF(state.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_ayrvokan_wireless_NativeDebug_control(JNIEnv *env, jclass, jstring action, jint depth, jint slot, jint value) {
    std::lock_guard<std::mutex> lock(guard); std::string next = utf(env, action);
    if (next == "detach") {
        if (armed && ti) ti->ClearBreakpoint(armed, armed_location);
        armed = nullptr; enabled = false; command.clear();
        if (ti) ti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, nullptr);
        publish("detached"); changed.notify_all();
    } else if (held) { edit_depth = depth; edit_slot = slot; edit_value = value; command = next; changed.notify_all(); }
    else if (next != "inspect") publish("control-error", ",\"error\":\"Target is not stopped at a Java breakpoint\"");
    return env->NewStringUTF(state.c_str());
}

extern "C" __attribute__((visibility("default"))) volatile int workshop_debug_native_value = 41;
extern "C" __attribute__((noinline, visibility("default"))) int workshop_debug_native_leaf(volatile int *value) { return *value + 1; }
extern "C" __attribute__((noinline, visibility("default"))) int workshop_debug_native_middle(volatile int *value) { int result = workshop_debug_native_leaf(value); return result; }

extern "C" JNIEXPORT jint JNICALL Java_com_ayrvokan_wireless_NativeDebug_nativeProbe(JNIEnv *, jclass) { return workshop_debug_native_middle(&workshop_debug_native_value); }
extern "C" JNIEXPORT jstring JNICALL Java_com_ayrvokan_wireless_NativeDebug_nativeInfo(JNIEnv *env, jclass) {
    Dl_info info = {}; dladdr(reinterpret_cast<void *>(&workshop_debug_native_leaf), &info);
    std::string result = "{\"pid\":" + std::to_string(getpid()) + ",\"library\":" + quote(info.dli_fname ? info.dli_fname : "") + ",\"base\":" + quote(hex(reinterpret_cast<uintptr_t>(info.dli_fbase))) + ",\"breakpointAddress\":" + quote(hex(reinterpret_cast<uintptr_t>(&workshop_debug_native_leaf))) + ",\"variableAddress\":" + quote(hex(reinterpret_cast<uintptr_t>(&workshop_debug_native_value))) + ",\"variableType\":\"volatile int32\",\"variableValue\":" + std::to_string(workshop_debug_native_value) + "}";
    return env->NewStringUTF(result.c_str());
}
