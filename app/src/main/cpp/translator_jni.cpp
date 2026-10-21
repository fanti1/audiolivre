// JNI bridge between io.github.fanti1.audiolivre.translate.SlimtTranslator and slimt, the engine that runs
// Firefox's translation models. Texts cross the boundary as UTF-8 byte arrays: JNI's own string functions use
// "modified UTF-8", which breaks characters outside the basic plane.

#include <jni.h>

#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "slimt/Frontend.hh"
#include "slimt/Io.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"

namespace {

using ModelPtr = std::shared_ptr<slimt::Model>;

void throw_java(JNIEnv *env, const std::string &message) {
  jclass error = env->FindClass("java/lang/IllegalStateException");
  if (error != nullptr) env->ThrowNew(error, message.c_str());
}

std::string utf8(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

// slimt's presets are for Mozilla's "tiny" models; the "base" ones have more layers. The parameter names in
// the model file say how many, so the architecture is read from the file instead of guessed.
void count_layers(const std::string &path, size_t &encoder, size_t &decoder) {
  encoder = 0;
  decoder = 0;
  slimt::io::MmapFile file(path);
  for (const auto &item : slimt::io::load_items(file.data())) {
    auto highest = [&item](const char *prefix, size_t &out) {
      const size_t length = std::strlen(prefix);
      if (item.name.compare(0, length, prefix) != 0) return;
      size_t index = 0;
      size_t position = length;
      while (position < item.name.size() && item.name[position] >= '0' && item.name[position] <= '9') {
        index = index * 10 + static_cast<size_t>(item.name[position] - '0');
        ++position;
      }
      if (position > length && index > out) out = index;
    };
    highest("encoder_l", encoder);
    highest("decoder_l", decoder);
  }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_fanti1_audiolivre_translate_SlimtTranslator_nativeNewService(JNIEnv *env, jclass, jint workers) {
  try {
    slimt::Config config;
    config.workers = workers > 0 ? static_cast<size_t>(workers) : 1;
    config.cache_size = 0;
    return reinterpret_cast<jlong>(new slimt::Async(config));
  } catch (const std::exception &e) {
    throw_java(env, e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_io_github_fanti1_audiolivre_translate_SlimtTranslator_nativeDeleteService(JNIEnv *, jclass, jlong service) {
  delete reinterpret_cast<slimt::Async *>(service);
}

JNIEXPORT jlong JNICALL Java_io_github_fanti1_audiolivre_translate_SlimtTranslator_nativeNewModel(
    JNIEnv *env, jclass, jstring model, jstring vocabulary, jstring shortlist, jstring target_vocabulary) {
  try {
    slimt::Package<std::string> package{
        .model = utf8(env, model),
        .vocabulary = utf8(env, vocabulary),
        .target_vocabulary = utf8(env, target_vocabulary),
        .shortlist = utf8(env, shortlist),
        .ssplit = std::string(),
    };
    slimt::Model::Config config = slimt::preset::tiny();
    size_t encoder = 0;
    size_t decoder = 0;
    count_layers(package.model, encoder, decoder);
    if (encoder > 0) config.encoder_layers = encoder;
    if (decoder > 0) config.decoder_layers = decoder;
    return reinterpret_cast<jlong>(new ModelPtr(std::make_shared<slimt::Model>(config, package)));
  } catch (const std::exception &e) {
    throw_java(env, std::string("não consegui carregar o tradutor: ") + e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_io_github_fanti1_audiolivre_translate_SlimtTranslator_nativeDeleteModel(JNIEnv *, jclass, jlong model) {
  delete reinterpret_cast<ModelPtr *>(model);
}

namespace {

// Runs every text through one model, all at once so the worker threads share the batch.
std::vector<std::string> translate_all(slimt::Async &service, const ModelPtr &model, std::vector<std::string> sources) {
  slimt::Options options;
  std::vector<slimt::Handle> handles;
  handles.reserve(sources.size());
  for (auto &source : sources) handles.push_back(service.translate(model, std::move(source), options));
  // Every request is waited for before any result is read, so none is left running in the engine.
  for (auto &handle : handles) handle.future().wait();
  std::vector<std::string> targets;
  targets.reserve(handles.size());
  for (auto &handle : handles) targets.push_back(handle.future().get().target.text);
  return targets;
}

}  // namespace

// Translates every input with `first`, or with `first` and then `second` when the route goes through English.
// The two legs run one after the other rather than through slimt's own pivot, which also maps word alignments
// across the legs and trips over some sentences; plain text is all the app needs.
JNIEXPORT jobjectArray JNICALL Java_io_github_fanti1_audiolivre_translate_SlimtTranslator_nativeTranslate(
    JNIEnv *env, jclass, jlong service_handle, jlong first_handle, jlong second_handle, jobjectArray inputs) {
  try {
    auto *service = reinterpret_cast<slimt::Async *>(service_handle);
    const ModelPtr &first = *reinterpret_cast<ModelPtr *>(first_handle);

    const jsize count = env->GetArrayLength(inputs);
    std::vector<std::string> sources;
    sources.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
      auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(inputs, i));
      const jsize length = env->GetArrayLength(bytes);
      std::string source(static_cast<size_t>(length), '\0');
      env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(source.data()));
      env->DeleteLocalRef(bytes);
      sources.push_back(std::move(source));
    }

    std::vector<std::string> targets = translate_all(*service, first, std::move(sources));
    if (second_handle != 0) {
      targets = translate_all(*service, *reinterpret_cast<ModelPtr *>(second_handle), std::move(targets));
    }

    jclass byte_array = env->FindClass("[B");
    jobjectArray results = env->NewObjectArray(count, byte_array, nullptr);
    for (jsize i = 0; i < count; ++i) {
      const std::string &target = targets[static_cast<size_t>(i)];
      jbyteArray bytes = env->NewByteArray(static_cast<jsize>(target.size()));
      env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(target.size()), reinterpret_cast<const jbyte *>(target.data()));
      env->SetObjectArrayElement(results, i, bytes);
      env->DeleteLocalRef(bytes);
    }
    return results;
  } catch (const std::exception &e) {
    throw_java(env, std::string("erro na tradução: ") + e.what());
    return nullptr;
  }
}

}  // extern "C"
