// Standalone CS-0.3/0.5 capability probe. No game state or SDK runtime needed.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#ifdef __ANDROID__
#include <adrenotools/driver.h>
#endif

static void check(VkResult r, const char* operation) {
  if (r != VK_SUCCESS) throw std::runtime_error(std::string(operation) + ": " + std::to_string(r));
}
static void* open_library(const char* path) {
#ifdef _WIN32
  return LoadLibraryA(path);
#else
  return dlopen(path, RTLD_NOW | RTLD_LOCAL);
#endif
}
static void* symbol(void* library, const char* name) {
#ifdef _WIN32
  return reinterpret_cast<void*>(GetProcAddress(static_cast<HMODULE>(library), name));
#else
  return dlsym(library, name);
#endif
}

#define FIELD(object, name) std::cout << ",\"" #name "\":" << object.name
#include "placed-map.h"

int main(int argc, char** argv) try {
  const char* placed_shader = nullptr;
  for (int i = 1; i < argc; ++i) {
    if (!strcmp(argv[i], "--placed")) {
      if (i + 1 >= argc) throw std::runtime_error("--placed requires SPIR-V path");
      placed_shader = argv[i + 1];
      for (int j = i; j + 2 < argc; ++j) argv[j] = argv[j + 2];
      argc -= 2;
      break;
    }
  }
#ifdef _WIN32
  const char* default_loader = "vulkan-1.dll";
#elif defined(__APPLE__)
  const char* default_loader = "libvulkan.1.dylib";
#else
  const char* default_loader = "libvulkan.so.1";
#endif
  const char* loader = argc > 1 ? argv[1] : default_loader;
  void* lib = nullptr;
#ifdef __ANDROID__
  // Same namespace hooks and driver bytes as the game, in a private tmp dir.
  // Android: driver directory (trailing /), hook directory, library filename.
  if (argc == 4) {
    lib = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                   argv[2], argv[1], argv[3], nullptr, nullptr);
  } else {
    if (argc == 1) loader = "/system/lib64/libvulkan.so";
    lib = open_library(loader);
  }
#else
  lib = open_library(loader);
#endif
  if (!lib) throw std::runtime_error(std::string("cannot load ") + loader);
  auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(symbol(lib, "vkGetInstanceProcAddr"));
  if (!gipa) throw std::runtime_error("loader has no vkGetInstanceProcAddr");
#define GLOBAL(name) auto name = reinterpret_cast<PFN_##name>(gipa(VK_NULL_HANDLE, #name)); if (!name) throw std::runtime_error(#name)
  GLOBAL(vkCreateInstance);
  GLOBAL(vkEnumerateInstanceExtensionProperties);
  uint32_t ie_count = 0;
  check(vkEnumerateInstanceExtensionProperties(nullptr, &ie_count, nullptr), "instance extensions");
  std::vector<VkExtensionProperties> ie(ie_count);
  check(vkEnumerateInstanceExtensionProperties(nullptr, &ie_count, ie.data()), "instance extensions");
  std::vector<const char*> instance_extensions;
  VkInstanceCreateFlags flags = 0;
  for (const auto& e : ie) {
    if (!strcmp(e.extensionName, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
      instance_extensions.push_back(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
      flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }
  }
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "clean-slate-stage0";
  app.apiVersion = VK_API_VERSION_1_2;
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ci.flags = flags;
  ci.pApplicationInfo = &app;
  ci.enabledExtensionCount = uint32_t(instance_extensions.size());
  ci.ppEnabledExtensionNames = instance_extensions.data();
  VkInstance instance;
  check(vkCreateInstance(&ci, nullptr, &instance), "vkCreateInstance");
#define INSTANCE(name) auto name = reinterpret_cast<PFN_##name>(gipa(instance, #name)); if (!name) throw std::runtime_error(#name)
  INSTANCE(vkDestroyInstance);
  INSTANCE(vkEnumeratePhysicalDevices);
  INSTANCE(vkEnumerateDeviceExtensionProperties);
  INSTANCE(vkGetPhysicalDeviceFeatures2);
  INSTANCE(vkGetPhysicalDeviceProperties2);
  uint32_t count = 0;
  check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "physical device count");
  if (!count) throw std::runtime_error("no physical devices");
  std::vector<VkPhysicalDevice> devices(count);
  check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "physical devices");
  std::cout << "{\"schema\":\"cs-vulkan.v1\",\"loader\":" << std::quoted(loader) << ",\"devices\":[";
  bool first = true;
  bool checks_passed = true;
  for (auto pd : devices) {
    uint32_t n = 0;
    check(vkEnumerateDeviceExtensionProperties(pd, nullptr, &n, nullptr), "device extension count");
    std::vector<VkExtensionProperties> extensions(n);
    check(vkEnumerateDeviceExtensionProperties(pd, nullptr, &n, extensions.data()), "device extensions");
    auto has = [&](const char* name) {
      for (const auto& e : extensions) if (!strcmp(e.extensionName, name)) return true;
      return false;
    };
    bool ext = has(VK_EXT_DEVICE_GENERATED_COMMANDS_EXTENSION_NAME);
    bool nv = has(VK_NV_DEVICE_GENERATED_COMMANDS_EXTENSION_NAME);
    bool placed = has(VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME);
    VkPhysicalDeviceDeviceGeneratedCommandsFeaturesEXT ef{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEVICE_GENERATED_COMMANDS_FEATURES_EXT};
    VkPhysicalDeviceDeviceGeneratedCommandsFeaturesNV nf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEVICE_GENERATED_COMMANDS_FEATURES_NV};
    VkPhysicalDeviceMapMemoryPlacedFeaturesEXT mf{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (ext) { ef.pNext = f.pNext; f.pNext = &ef; }
    if (nv) { nf.pNext = f.pNext; f.pNext = &nf; }
    if (placed) { mf.pNext = f.pNext; f.pNext = &mf; }
    vkGetPhysicalDeviceFeatures2(pd, &f);
    VkPhysicalDeviceDeviceGeneratedCommandsPropertiesEXT ep{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEVICE_GENERATED_COMMANDS_PROPERTIES_EXT};
    VkPhysicalDeviceDeviceGeneratedCommandsPropertiesNV np{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DEVICE_GENERATED_COMMANDS_PROPERTIES_NV};
    VkPhysicalDeviceMapMemoryPlacedPropertiesEXT mp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT};
    VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &driver};
    if (ext) { ep.pNext = props.pNext; props.pNext = &ep; }
    if (nv) { np.pNext = props.pNext; props.pNext = &np; }
    if (placed) { mp.pNext = props.pNext; props.pNext = &mp; }
    vkGetPhysicalDeviceProperties2(pd, &props);
    if (!first) std::cout << ',';
    first = false;
    std::cout << "{\"device\":" << std::quoted(props.properties.deviceName)
              << ",\"apiVersion\":" << props.properties.apiVersion
              << ",\"driverVersion\":" << props.properties.driverVersion
              << ",\"driverName\":" << std::quoted(driver.driverName)
              << ",\"driverInfo\":" << std::quoted(driver.driverInfo)
              << ",\"EXT_DGC\":{\"advertised\":" << ext;
    FIELD(ef, deviceGeneratedCommands); FIELD(ef, dynamicGeneratedPipelineLayout);
    FIELD(ep, maxIndirectPipelineCount); FIELD(ep, maxIndirectShaderObjectCount);
    FIELD(ep, maxIndirectSequenceCount); FIELD(ep, maxIndirectCommandsTokenCount);
    FIELD(ep, maxIndirectCommandsTokenOffset); FIELD(ep, maxIndirectCommandsIndirectStride);
    FIELD(ep, supportedIndirectCommandsInputModes); FIELD(ep, supportedIndirectCommandsShaderStages);
    FIELD(ep, supportedIndirectCommandsShaderStagesPipelineBinding);
    FIELD(ep, supportedIndirectCommandsShaderStagesShaderBinding);
    FIELD(ep, deviceGeneratedCommandsTransformFeedback); FIELD(ep, deviceGeneratedCommandsMultiDrawIndirectCount);
    std::cout << ",\"graphicsPipelineExecutionSets\":" << (ext && ef.deviceGeneratedCommands && ep.maxIndirectPipelineCount > 1 &&
        (ep.supportedIndirectCommandsShaderStagesPipelineBinding & (VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)) ==
        (VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)) << "},\"NV_DGC\":{\"advertised\":" << nv;
    FIELD(nf, deviceGeneratedCommands); FIELD(np, maxGraphicsShaderGroupCount); FIELD(np, maxIndirectSequenceCount);
    FIELD(np, maxIndirectCommandsTokenCount); FIELD(np, maxIndirectCommandsStreamCount);
    FIELD(np, maxIndirectCommandsTokenOffset); FIELD(np, maxIndirectCommandsStreamStride);
    FIELD(np, minSequencesCountBufferOffsetAlignment); FIELD(np, minSequencesIndexBufferOffsetAlignment);
    FIELD(np, minIndirectCommandsBufferOffsetAlignment);
    std::cout << "},\"placedMap\":{\"advertised\":" << placed;
    FIELD(mf, memoryMapPlaced); FIELD(mf, memoryMapRangePlaced); FIELD(mf, memoryUnmapReserve);
    FIELD(mp, minPlacedMemoryMapAlignment);
    std::cout << '}';
    if (placed_shader && placed && mf.memoryMapPlaced)
      checks_passed &= placed_map(gipa, instance, pd, props.properties, mf, mp.minPlacedMemoryMapAlignment, placed_shader);
    std::cout << '}';
  }
  std::cout << "]}\n";
  vkDestroyInstance(instance, nullptr);
  return checks_passed ? 0 : 2;
} catch (const std::exception& e) {
  std::cerr << "cs-vulkan: " << e.what() << '\n';
  return 1;
}
