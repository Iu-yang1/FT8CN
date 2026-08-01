#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#if FT8CN_NATIVE_HAMLIB_ACTIVE
#include <hamlib/rig.h>
#include <hamlib/port.h>
#endif

namespace {

std::mutex g_hamlib_mutex;

void throw_java(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message == nullptr ? "Hamlib error" : message);
    }
}

std::string from_java(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

#if FT8CN_NATIVE_HAMLIB_ACTIVE

RIG* g_rig = nullptr;

bool check_result(JNIEnv* env, int result, const char* operation) {
    if (result == RIG_OK) return true;
    std::string message(operation);
    message.append(": ").append(rigerror(result));
    throw_java(env, message.c_str());
    return false;
}

bool set_configuration(JNIEnv* env, RIG* rig, const char* key, const std::string& value) {
    if (value.empty()) return true;
    hamlib_token_t token = rig_token_lookup(rig, key);
    if (token == RIG_CONF_END) {
        std::string message("Hamlib configuration is unavailable: ");
        message.append(key);
        throw_java(env, message.c_str());
        return false;
    }
    return check_result(env, rig_set_conf(rig, token, value.c_str()), key);
}

RIG* require_rig(JNIEnv* env, jlong handle) {
    RIG* rig = reinterpret_cast<RIG*>(static_cast<intptr_t>(handle));
    if (rig == nullptr || rig != g_rig) {
        throw_java(env, "Hamlib handle is closed");
        return nullptr;
    }
    return rig;
}

struct ModelListContext {
    std::vector<std::string> rows;
};

int append_model(const struct rig_caps* caps, rig_ptr_t opaque) {
    if (caps == nullptr || opaque == nullptr || caps->rig_model <= 0) return 1;
    auto* context = static_cast<ModelListContext*>(opaque);
    std::string row = std::to_string(caps->rig_model);
    row.append("\t").append(caps->mfg_name == nullptr ? "" : caps->mfg_name);
    row.append("\t").append(caps->model_name == nullptr ? "" : caps->model_name);
    row.append("\t").append(caps->version == nullptr ? "" : caps->version);
    context->rows.push_back(std::move(row));
    return 1;
}

rmode_t to_hamlib_mode(jint mode) {
    switch (mode) {
        case 0: return RIG_MODE_USB;
        case 1: return RIG_MODE_PKTUSB;
        case 2: return RIG_MODE_FM;
        case 3: return RIG_MODE_CW;
        default: return RIG_MODE_NONE;
    }
}

jint from_hamlib_mode(rmode_t mode) {
    if ((mode & RIG_MODE_PKTUSB) != 0) return 1;
    if ((mode & RIG_MODE_USB) != 0) return 0;
    if ((mode & RIG_MODE_FM) != 0) return 2;
    if ((mode & RIG_MODE_CW) != 0) return 3;
    return -1;
}

#endif

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeAvailable(JNIEnv*, jclass) {
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeVersion(JNIEnv* env, jclass) {
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    return env->NewStringUTF(hamlib_version);
#else
    return env->NewStringUTF("unavailable");
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeListModels(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
    jclass string_class = env->FindClass("java/lang/String");
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    rig_load_all_backends();
    ModelListContext context;
    rig_list_foreach(append_model, &context);
    std::sort(context.rows.begin(), context.rows.end());
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(context.rows.size()), string_class, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(context.rows.size()); ++i) {
        jstring row = env->NewStringUTF(context.rows[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(result, i, row);
        env->DeleteLocalRef(row);
    }
    return result;
#else
    return env->NewObjectArray(0, string_class, nullptr);
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeOpen(
        JNIEnv* env,
        jclass,
        jint model,
        jstring endpoint,
        jint baud,
        jint data_bits,
        jint stop_bits,
        jstring handshake,
        jstring force_dtr,
        jstring force_rts,
        jstring ptt_type,
        jstring ptt_endpoint,
        jint poll_interval_ms,
        jint tx_delay_ms,
        jboolean auto_power_on,
        jboolean auto_power_off) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    if (g_rig != nullptr) {
        rig_close(g_rig);
        rig_cleanup(g_rig);
        g_rig = nullptr;
    }
    rig_load_all_backends();
    RIG* rig = rig_init(static_cast<rig_model_t>(model));
    if (rig == nullptr) {
        throw_java(env, "Hamlib cannot initialize the selected model");
        return 0;
    }

    std::string endpoint_value = from_java(env, endpoint);
    const std::string bridge_prefix = "tcp://";
    const bool network_bridge = endpoint_value.rfind(bridge_prefix, 0) == 0;
    if (network_bridge) {
        endpoint_value.erase(0, bridge_prefix.size());
        hamlib_port_t* port = HAMLIB_RIGPORT(rig);
        if (port == nullptr) {
            rig_cleanup(rig);
            throw_java(env, "Hamlib did not expose a CAT port");
            return 0;
        }
        port->type.rig = RIG_PORT_NETWORK;
    }

    std::vector<std::pair<const char*, std::string>> configuration = {
        {"rig_pathname", endpoint_value},
        {"ptt_type", from_java(env, ptt_type)},
        {"ptt_pathname", from_java(env, ptt_endpoint)},
        {"poll_interval", std::to_string(poll_interval_ms)},
        {"post_ptt_delay", std::to_string(tx_delay_ms)},
        {"auto_power_on", auto_power_on == JNI_TRUE ? "1" : "0"},
        {"auto_power_off", auto_power_off == JNI_TRUE ? "1" : "0"},
        {"client", "WSJTX"},
    };
    if (!network_bridge) {
        configuration.insert(configuration.begin() + 1, {
            {"serial_speed", std::to_string(baud)},
            {"data_bits", data_bits == 0 ? "" : std::to_string(data_bits)},
            {"stop_bits", stop_bits == 0 ? "" : std::to_string(stop_bits)},
            {"serial_handshake", from_java(env, handshake)},
            {"dtr_state", from_java(env, force_dtr)},
            {"rts_state", from_java(env, force_rts)},
        });
    }
    for (const auto& item : configuration) {
        if (!set_configuration(env, rig, item.first, item.second)) {
            rig_cleanup(rig);
            return 0;
        }
    }
    if (!check_result(env, rig_open(rig), "rig_open")) {
        rig_cleanup(rig);
        return 0;
    }
    g_rig = rig;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(rig));
#else
    throw_java(env, "Native Hamlib was not built for this ABI");
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeClose(
        JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    rig_set_ptt(rig, RIG_VFO_CURR, RIG_PTT_OFF);
    rig_close(rig);
    rig_cleanup(rig);
    g_rig = nullptr;
#else
    (void) env;
    (void) handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeSetFrequency(
        JNIEnv* env, jclass, jlong handle, jlong rx_hz, jlong tx_hz) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    if (!check_result(env, rig_set_freq(rig, RIG_VFO_CURR, static_cast<freq_t>(rx_hz)), "rig_set_freq")) return;
    if (tx_hz != rx_hz) {
        if (!check_result(env, rig_set_split_vfo(rig, RIG_VFO_CURR, RIG_SPLIT_ON, RIG_VFO_B), "rig_set_split_vfo")) return;
        check_result(env, rig_set_split_freq(rig, RIG_VFO_CURR, static_cast<freq_t>(tx_hz)), "rig_set_split_freq");
    } else {
        check_result(env, rig_set_split_vfo(rig, RIG_VFO_CURR, RIG_SPLIT_OFF, RIG_VFO_A), "rig_disable_split");
    }
#else
    throw_java(env, "Native Hamlib is unavailable");
#endif
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeGetFrequency(
        JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
    jlong values[2] = {0, 0};
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return nullptr;
    freq_t rx = 0;
    if (!check_result(env, rig_get_freq(rig, RIG_VFO_CURR, &rx), "rig_get_freq")) return nullptr;
    split_t split = RIG_SPLIT_OFF;
    vfo_t tx_vfo = RIG_VFO_B;
    freq_t tx = rx;
    if (rig_get_split_vfo(rig, RIG_VFO_CURR, &split, &tx_vfo) == RIG_OK && split == RIG_SPLIT_ON) {
        rig_get_split_freq(rig, RIG_VFO_CURR, &tx);
    }
    values[0] = static_cast<jlong>(rx);
    values[1] = static_cast<jlong>(tx);
#endif
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeSetMode(
        JNIEnv* env, jclass, jlong handle, jint mode, jint passband_hz) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    rmode_t hamlib_mode = to_hamlib_mode(mode);
    if (hamlib_mode == RIG_MODE_NONE) {
        throw_java(env, "Unsupported Hamlib mode");
        return;
    }
    check_result(env, rig_set_mode(rig, RIG_VFO_CURR, hamlib_mode, passband_hz), "rig_set_mode");
#else
    throw_java(env, "Native Hamlib is unavailable");
#endif
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeGetMode(
        JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
    jlong values[2] = {-1, 0};
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return nullptr;
    rmode_t mode = RIG_MODE_NONE;
    pbwidth_t width = 0;
    if (!check_result(env, rig_get_mode(rig, RIG_VFO_CURR, &mode, &width), "rig_get_mode")) return nullptr;
    values[0] = from_hamlib_mode(mode);
    values[1] = static_cast<jlong>(width);
#endif
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeSetVfo(
        JNIEnv* env, jclass, jlong handle, jint vfo) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    vfo_t target = vfo == 1 ? RIG_VFO_A : (vfo == 2 ? RIG_VFO_B : RIG_VFO_CURR);
    check_result(env, rig_set_vfo(rig, target), "rig_set_vfo");
#else
    throw_java(env, "Native Hamlib is unavailable");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeSetSplit(
        JNIEnv* env, jclass, jlong handle, jboolean enabled, jint tx_vfo) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    vfo_t target = tx_vfo == 1 ? RIG_VFO_A : RIG_VFO_B;
    check_result(
            env,
            rig_set_split_vfo(
                    rig,
                    RIG_VFO_CURR,
                    enabled == JNI_TRUE ? RIG_SPLIT_ON : RIG_SPLIT_OFF,
                    target),
            "rig_set_split_vfo");
#else
    throw_java(env, "Native Hamlib is unavailable");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeSetPtt(
        JNIEnv* env, jclass, jlong handle, jboolean enabled, jboolean data_audio) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return;
    ptt_t ptt = enabled == JNI_TRUE
            ? (data_audio == JNI_TRUE ? RIG_PTT_ON_DATA : RIG_PTT_ON_MIC)
            : RIG_PTT_OFF;
    check_result(env, rig_set_ptt(rig, RIG_VFO_CURR, ptt), "rig_set_ptt");
#else
    throw_java(env, "Native Hamlib is unavailable");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeGetPtt(
        JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return JNI_FALSE;
    ptt_t ptt = RIG_PTT_OFF;
    if (!check_result(env, rig_get_ptt(rig, RIG_VFO_CURR, &ptt), "rig_get_ptt")) return JNI_FALSE;
    return ptt == RIG_PTT_OFF ? JNI_FALSE : JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_bg7yoz_ft8cn_core_radio_NativeHamlibBridge_nativeGetStrength(
        JNIEnv* env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hamlib_mutex);
#if FT8CN_NATIVE_HAMLIB_ACTIVE
    RIG* rig = require_rig(env, handle);
    if (rig == nullptr) return std::numeric_limits<jfloat>::quiet_NaN();
    value_t value{};
    const int result = rig_get_level(rig, RIG_VFO_CURR, RIG_LEVEL_STRENGTH, &value);
    if (result == RIG_ENAVAIL || result == RIG_ENIMPL) {
        return std::numeric_limits<jfloat>::quiet_NaN();
    }
    if (!check_result(env, result, "rig_get_strength")) {
        return std::numeric_limits<jfloat>::quiet_NaN();
    }
    return static_cast<jfloat>(value.f);
#else
    (void) env;
    (void) handle;
    return std::numeric_limits<jfloat>::quiet_NaN();
#endif
}
