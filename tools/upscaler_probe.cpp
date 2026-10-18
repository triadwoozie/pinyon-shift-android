// upscaler_probe: report which upscaler and frame generation runtimes the
// local Vulkan device can run (UPSCALING_BACKLOG UP-0.2).
//
// It creates a Vulkan 1.3 device on the first discrete GPU, then asks each
// runtime that is present whether it supports that device:
//   - DLSS super resolution and frame generation through NGX (static NGX
//     library from the DLSS SDK, feature DLLs from --dlss-dir);
//   - XeSS super resolution (libxess.dll from --xess-dir);
//   - FSR 3.1 upscaling (amd_fidelityfx_vk.dll from --ffx-dir, FidelityFX
//     SDK 1.1.x).
// It also lists the device extensions the backlog's latency, interop and
// Metal items depend on. Nothing is dispatched; contexts are created and
// destroyed. The SDKs are fetched by hand into .local/upscaler-sdks (see the
// backlog's UP-0.2 row); no SDK file is part of this repository.
//
// Build (Windows, inside vcvars64, with the toolchain's clang-cl):
//   clang-cl /std:c++20 /EHsc /MD /O2 tools/upscaler_probe.cpp
//     /I thirdparty/shiftglue-sdk/thirdparty/vulkan-headers/include
//     /I <DLSS>/include /I <XeSS>/inc /I <FFX114>/ffx-api/include
//     /link <DLSS>/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib advapi32.lib
//     user32.lib /out:.local/upscaler-probe/upscaler_probe.exe
// Run:
//   upscaler_probe --dlss-dir <DLSS>/lib/Windows_x86_64/rel
//     --xess-dir <XeSS>/bin --ffx-dir <FFX114>/PrebuiltSignedDLL
//     --log-dir <writable folder for NGX logs>

#define VK_NO_PROTOTYPES
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>

#include <nvsdk_ngx.h>
#include <nvsdk_ngx_defs_dlssg.h>
#include <nvsdk_ngx_vk.h>

#include <xess/xess.h>
#include <xess/xess_vk.h>

#include <ffx_api/ffx_api.h>
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/ffx_framegeneration.h>
#include <ffx_api/vk/ffx_api_vk.h>

#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

namespace {

PFN_vkGetInstanceProcAddr gipa;
PFN_vkGetDeviceProcAddr gdpa;

template <typename T>
T InstanceProc(VkInstance instance, const char* name) {
  return reinterpret_cast<T>(gipa(instance, name));
}

std::wstring Widen(const std::string& s) {
  return std::wstring(s.begin(), s.end());
}

struct Options {
  std::string dlss_dir;
  std::string xess_dir;
  std::string ffx_dir;
  std::string log_dir = ".";
};

bool HasExtension(const std::vector<VkExtensionProperties>& list,
                  const char* name) {
  for (const auto& e : list) {
    if (std::strcmp(e.extensionName, name) == 0) return true;
  }
  return false;
}

// XeSS entry points, loaded at run time so the probe runs without the DLL.
struct Xess {
  HMODULE module = nullptr;
  decltype(&xessGetVersion) GetVersion = nullptr;
  decltype(&xessVKGetRequiredInstanceExtensions) GetInstanceExtensions = nullptr;
  decltype(&xessVKGetRequiredDeviceExtensions) GetDeviceExtensions = nullptr;
  decltype(&xessVKGetRequiredDeviceFeatures) GetDeviceFeatures = nullptr;
  decltype(&xessVKCreateContext) CreateContext = nullptr;
  decltype(&xessGetOptimalInputResolution) GetOptimalInputResolution = nullptr;
  decltype(&xessDestroyContext) DestroyContext = nullptr;

  bool Load(const std::string& dir) {
    if (dir.empty()) return false;
    SetDllDirectoryA(dir.c_str());
    module = LoadLibraryA((dir + "\\libxess.dll").c_str());
    SetDllDirectoryA(nullptr);
    if (!module) return false;
    auto get = [&](auto& fn, const char* name) {
      fn = reinterpret_cast<std::remove_reference_t<decltype(fn)>>(
          GetProcAddress(module, name));
      return fn != nullptr;
    };
    return get(GetVersion, "xessGetVersion") &&
           get(GetInstanceExtensions, "xessVKGetRequiredInstanceExtensions") &&
           get(GetDeviceExtensions, "xessVKGetRequiredDeviceExtensions") &&
           get(GetDeviceFeatures, "xessVKGetRequiredDeviceFeatures") &&
           get(CreateContext, "xessVKCreateContext") &&
           get(GetOptimalInputResolution, "xessGetOptimalInputResolution") &&
           get(DestroyContext, "xessDestroyContext");
  }
};

// The NGX project id for this probe: any GUID, with the custom engine type,
// as the NGX headers instruct when NVIDIA has not assigned an id.
constexpr const char* kNgxProjectId = "6f1d6c3e-5a9b-4c0e-9a51-2b7f3e8d4c21";

NVSDK_NGX_FeatureDiscoveryInfo NgxDiscovery(NVSDK_NGX_Feature feature,
                                            const wchar_t* data_path,
                                            const NVSDK_NGX_FeatureCommonInfo* info) {
  NVSDK_NGX_FeatureDiscoveryInfo d{};
  d.SDKVersion = NVSDK_NGX_Version_API;
  d.FeatureID = feature;
  d.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
  d.Identifier.v.ProjectDesc.ProjectId = kNgxProjectId;
  d.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
  d.Identifier.v.ProjectDesc.EngineVersion = "1.0";
  d.ApplicationDataPath = data_path;
  d.FeatureInfo = info;
  return d;
}

void PrintNgxRequirement(const char* label, NVSDK_NGX_Result r,
                         const NVSDK_NGX_FeatureRequirement& req) {
  if (NVSDK_NGX_FAILED(r)) {
    std::printf("  %-28s query failed (0x%08x)\n", label, unsigned(r));
    return;
  }
  std::printf("  %-28s %s (support bits 0x%x, min arch 0x%x, min OS %s)\n",
              label, req.FeatureSupported == 0 ? "supported" : "NOT supported",
              unsigned(req.FeatureSupported), req.MinHWArchitecture,
              req.MinOSVersion);
}

// Reports device functions a runtime asks for that the device lacks.
PFN_vkVoidFunction VKAPI_CALL LoggingDeviceProcAddr(VkDevice device, const char* name) {
  auto fn = gdpa(device, name);
  if (!fn) std::printf("  (no device function %s)\n", name);
  return fn;
}

void FfxMessage(uint32_t type, const wchar_t* message) {
  std::printf("  ffx message %u: %ls\n", type, message);
}

// Names the module and offset of a crash inside a vendor runtime.
LONG WINAPI ReportCrash(EXCEPTION_POINTERS* info) {
  if (info->ExceptionRecord->ExceptionCode != EXCEPTION_ACCESS_VIOLATION) {
    return EXCEPTION_CONTINUE_SEARCH;
  }
  void* address = info->ExceptionRecord->ExceptionAddress;
  HMODULE module = nullptr;
  char name[MAX_PATH] = "?";
  if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                             GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                         static_cast<LPCSTR>(address), &module)) {
    GetModuleFileNameA(module, name, MAX_PATH);
  }
  std::printf("  access violation in %s+0x%llx (address 0x%llx)\n", name,
              static_cast<unsigned long long>(static_cast<char*>(address) -
                                              reinterpret_cast<char*>(module)),
              static_cast<unsigned long long>(info->ExceptionRecord->ExceptionInformation[1]));
  ExitProcess(3);
}

}  // namespace

int main(int argc, char** argv) {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  AddVectoredExceptionHandler(0, ReportCrash);
  Options opt;
  for (int i = 1; i + 1 < argc; i += 2) {
    std::string k = argv[i];
    if (k == "--dlss-dir") opt.dlss_dir = argv[i + 1];
    else if (k == "--xess-dir") opt.xess_dir = argv[i + 1];
    else if (k == "--ffx-dir") opt.ffx_dir = argv[i + 1];
    else if (k == "--log-dir") opt.log_dir = argv[i + 1];
  }

  HMODULE vk = LoadLibraryA("vulkan-1.dll");
  if (!vk) {
    std::printf("no Vulkan loader\n");
    return 1;
  }
  gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
      GetProcAddress(vk, "vkGetInstanceProcAddr"));

  Xess xess;
  bool have_xess = xess.Load(opt.xess_dir);

  std::wstring data_path = Widen(opt.log_dir);
  std::wstring dlss_path = Widen(opt.dlss_dir);
  const wchar_t* dlss_paths[] = {dlss_path.c_str()};
  NVSDK_NGX_FeatureCommonInfo ngx_info{};
  ngx_info.PathListInfo.Path = dlss_paths;
  ngx_info.PathListInfo.Length = opt.dlss_dir.empty() ? 0 : 1;

  // Instance: Vulkan 1.3 plus whatever NGX and XeSS ask for.
  auto enumerate_instance_ext = InstanceProc<PFN_vkEnumerateInstanceExtensionProperties>(
      nullptr, "vkEnumerateInstanceExtensionProperties");
  uint32_t n = 0;
  enumerate_instance_ext(nullptr, &n, nullptr);
  std::vector<VkExtensionProperties> instance_available(n);
  enumerate_instance_ext(nullptr, &n, instance_available.data());

  std::set<std::string> instance_wanted;
  for (auto feature : {NVSDK_NGX_Feature_SuperSampling, NVSDK_NGX_Feature_FrameGeneration}) {
    auto d = NgxDiscovery(feature, data_path.c_str(), &ngx_info);
    uint32_t count = 0;
    VkExtensionProperties* props = nullptr;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(
            &d, &count, &props))) {
      for (uint32_t i = 0; i < count; ++i) instance_wanted.insert(props[i].extensionName);
    }
  }
  if (have_xess) {
    uint32_t count = 0;
    const char* const* names = nullptr;
    uint32_t min_api = 0;
    if (xess.GetInstanceExtensions(&count, &names, &min_api) == XESS_RESULT_SUCCESS) {
      for (uint32_t i = 0; i < count; ++i) instance_wanted.insert(names[i]);
    }
  }
  // The FidelityFX Vulkan backend names its objects through debug utils.
  instance_wanted.insert(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
  std::vector<const char*> instance_ext;
  for (const auto& e : instance_wanted) {
    if (HasExtension(instance_available, e.c_str())) instance_ext.push_back(e.c_str());
  }

  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "upscaler_probe";
  app.apiVersion = VK_API_VERSION_1_3;
  VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ici.pApplicationInfo = &app;
  ici.enabledExtensionCount = uint32_t(instance_ext.size());
  ici.ppEnabledExtensionNames = instance_ext.data();
  VkInstance instance = VK_NULL_HANDLE;
  if (InstanceProc<PFN_vkCreateInstance>(nullptr, "vkCreateInstance")(&ici, nullptr, &instance) !=
      VK_SUCCESS) {
    std::printf("vkCreateInstance failed\n");
    return 1;
  }
  gdpa = InstanceProc<PFN_vkGetDeviceProcAddr>(instance, "vkGetDeviceProcAddr");

  auto enumerate_pd = InstanceProc<PFN_vkEnumeratePhysicalDevices>(instance, "vkEnumeratePhysicalDevices");
  auto get_props = InstanceProc<PFN_vkGetPhysicalDeviceProperties2>(instance, "vkGetPhysicalDeviceProperties2");
  auto get_features = InstanceProc<PFN_vkGetPhysicalDeviceFeatures2>(instance, "vkGetPhysicalDeviceFeatures2");
  auto enumerate_device_ext = InstanceProc<PFN_vkEnumerateDeviceExtensionProperties>(
      instance, "vkEnumerateDeviceExtensionProperties");
  n = 0;
  enumerate_pd(instance, &n, nullptr);
  std::vector<VkPhysicalDevice> pds(n);
  enumerate_pd(instance, &n, pds.data());
  VkPhysicalDevice pd = VK_NULL_HANDLE;
  VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
  VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &driver};
  for (auto candidate : pds) {
    get_props(candidate, &props);
    if (props.properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU || !pd) pd = candidate;
    if (props.properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) break;
  }
  get_props(pd, &props);
  std::printf("Device: %s\nDriver: %s %s\nVulkan: %u.%u.%u\n\n", props.properties.deviceName,
              driver.driverName, driver.driverInfo, VK_API_VERSION_MAJOR(props.properties.apiVersion),
              VK_API_VERSION_MINOR(props.properties.apiVersion),
              VK_API_VERSION_PATCH(props.properties.apiVersion));

  n = 0;
  enumerate_device_ext(pd, nullptr, &n, nullptr);
  std::vector<VkExtensionProperties> device_available(n);
  enumerate_device_ext(pd, nullptr, &n, device_available.data());

  VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
  VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, &f12};
  VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f13};
  get_features(pd, &f2);

  std::printf("Device extensions and features the backlog depends on:\n");
  for (const char* e : {"VK_NV_low_latency2", "VK_AMD_anti_lag", "VK_KHR_present_id",
                        "VK_KHR_present_wait", "VK_KHR_external_memory_win32",
                        "VK_KHR_external_semaphore_win32", "VK_NVX_binary_import",
                        "VK_NVX_image_view_handle", "VK_NV_optical_flow",
                        "VK_KHR_shader_integer_dot_product"}) {
    std::printf("  %-34s %s\n", e, HasExtension(device_available, e) ? "yes" : "no");
  }
  std::printf("  %-34s %s\n", "shaderFloat16", f12.shaderFloat16 ? "yes" : "no");
  std::printf("  %-34s %s\n\n", "shaderIntegerDotProduct", f13.shaderIntegerDotProduct ? "yes" : "no");

  // NGX feature discovery needs no device.
  std::printf("NVIDIA NGX (DLSS SDK %s):\n", opt.dlss_dir.empty() ? "(no --dlss-dir)" : "");
  for (auto [feature, label] : {std::pair{NVSDK_NGX_Feature_SuperSampling, "DLSS super resolution"},
                                std::pair{NVSDK_NGX_Feature_FrameGeneration, "DLSS frame generation"}}) {
    auto d = NgxDiscovery(feature, data_path.c_str(), &ngx_info);
    NVSDK_NGX_FeatureRequirement req{};
    auto r = NVSDK_NGX_VULKAN_GetFeatureRequirements(instance, pd, &d, &req);
    PrintNgxRequirement(label, r, req);
  }

  // Device: union of NGX's and XeSS's required extensions, plus the
  // feature chain XeSS asks for.
  std::set<std::string> device_wanted;
  for (auto feature : {NVSDK_NGX_Feature_SuperSampling, NVSDK_NGX_Feature_FrameGeneration}) {
    auto d = NgxDiscovery(feature, data_path.c_str(), &ngx_info);
    uint32_t count = 0;
    VkExtensionProperties* exts = nullptr;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            instance, pd, &d, &count, &exts))) {
      for (uint32_t i = 0; i < count; ++i) device_wanted.insert(exts[i].extensionName);
    }
  }
  // Enable every core feature the device has; XeSS patches this chain.
  void* feature_chain = &f2;
  if (have_xess) {
    uint32_t count = 0;
    const char* const* names = nullptr;
    if (xess.GetDeviceExtensions(instance, pd, &count, &names) == XESS_RESULT_SUCCESS) {
      for (uint32_t i = 0; i < count; ++i) device_wanted.insert(names[i]);
    }
    xess.GetDeviceFeatures(instance, pd, &feature_chain);
  }
  // The FidelityFX 1.1.x Vulkan backend picks code paths from the
  // extensions the device offers, and loads KHR-suffixed entry points
  // (vkGetBufferMemoryRequirements2KHR) even on Vulkan 1.3, where they only
  // resolve with the extension enabled; without it, context creation calls
  // a null pointer.
  for (const char* e : {VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME,
                        VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME,
                        VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
                        VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
                        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                        VK_AMD_BUFFER_MARKER_EXTENSION_NAME,
                        VK_AMD_DEVICE_COHERENT_MEMORY_EXTENSION_NAME}) {
    if (HasExtension(device_available, e)) device_wanted.insert(e);
  }
  std::vector<const char*> device_ext;
  for (const auto& e : device_wanted) {
    if (HasExtension(device_available, e.c_str())) device_ext.push_back(e.c_str());
    else std::printf("  (requested device extension %s is not available)\n", e.c_str());
  }

  float priority = 1.0f;
  VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qci.queueFamilyIndex = 0;
  qci.queueCount = 1;
  qci.pQueuePriorities = &priority;
  VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  dci.pNext = feature_chain;
  dci.queueCreateInfoCount = 1;
  dci.pQueueCreateInfos = &qci;
  dci.enabledExtensionCount = uint32_t(device_ext.size());
  dci.ppEnabledExtensionNames = device_ext.data();
  VkDevice device = VK_NULL_HANDLE;
  VkResult dr = InstanceProc<PFN_vkCreateDevice>(instance, "vkCreateDevice")(pd, &dci, nullptr, &device);
  if (dr != VK_SUCCESS) {
    std::printf("vkCreateDevice failed (%d)\n", int(dr));
    return 1;
  }

  // NGX on the device: capability parameters say what this driver and the
  // DLLs in --dlss-dir can create.
  auto ngx = NVSDK_NGX_VULKAN_Init_with_ProjectID(kNgxProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.0",
                                                  data_path.c_str(), instance, pd, device, gipa, gdpa,
                                                  &ngx_info);
  if (NVSDK_NGX_SUCCEED(ngx)) {
    NVSDK_NGX_Parameter* caps = nullptr;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetCapabilityParameters(&caps)) && caps) {
      auto report = [&](const char* label, const char* available, const char* init_result,
                        const char* needs_driver) {
        int a = 0, init = 0, nd = 0;
        caps->Get(available, &a);
        caps->Get(init_result, &init);
        caps->Get(needs_driver, &nd);
        std::printf("  %-28s available=%d init=0x%08x needs-newer-driver=%d\n", label, a,
                    unsigned(init), nd);
      };
      report("DLSS SR on this device", NVSDK_NGX_Parameter_SuperSampling_Available,
             NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult,
             NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver);
      report("DLSS FG on this device", NVSDK_NGX_Parameter_FrameGeneration_Available,
             NVSDK_NGX_Parameter_FrameGeneration_FeatureInitResult,
             NVSDK_NGX_Parameter_FrameGeneration_NeedsUpdatedDriver);
      NVSDK_NGX_VULKAN_DestroyParameters(caps);
    }
    NVSDK_NGX_VULKAN_Shutdown1(device);
  } else {
    std::printf("  NGX init failed (0x%08x)\n", unsigned(ngx));
  }

  // XeSS: create a context and ask for the 4K Quality input size.
  std::printf("\nIntel XeSS:\n");
  if (have_xess) {
    xess_version_t v{};
    xess.GetVersion(&v);
    xess_context_handle_t ctx = nullptr;
    auto r = xess.CreateContext(instance, pd, device, &ctx);
    std::printf("  libxess %u.%u.%u, Vulkan context: %s (%d)\n", v.major, v.minor, v.patch,
                r == XESS_RESULT_SUCCESS ? "created" : "failed", int(r));
    if (r == XESS_RESULT_SUCCESS) {
      xess_2d_t out{3840, 2160}, optimal{}, mn{}, mx{};
      if (xess.GetOptimalInputResolution(ctx, &out, XESS_QUALITY_SETTING_QUALITY, &optimal, &mn, &mx) ==
          XESS_RESULT_SUCCESS) {
        std::printf("  4K Quality input: %ux%u (range %ux%u to %ux%u)\n", optimal.x, optimal.y, mn.x,
                    mn.y, mx.x, mx.y);
      }
      xess.DestroyContext(ctx);
    }
  } else {
    std::printf("  libxess.dll not found (--xess-dir)\n");
  }

  // FidelityFX 1.1.x Vulkan DLL: versions, then an upscaler context.
  std::printf("\nAMD FidelityFX (Vulkan DLL):\n");
  HMODULE ffx = opt.ffx_dir.empty() ? nullptr
                                    : LoadLibraryA((opt.ffx_dir + "\\amd_fidelityfx_vk.dll").c_str());
  if (ffx) {
    auto query = reinterpret_cast<PfnFfxQuery>(GetProcAddress(ffx, "ffxQuery"));
    auto create = reinterpret_cast<PfnFfxCreateContext>(GetProcAddress(ffx, "ffxCreateContext"));
    auto destroy = reinterpret_cast<PfnFfxDestroyContext>(GetProcAddress(ffx, "ffxDestroyContext"));
    for (auto [type, label] : {std::pair{uint64_t(FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE), "upscaler"},
                               std::pair{uint64_t(FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION),
                                         "frame generation"}}) {
      uint64_t count = 8;
      uint64_t ids[8] = {};
      const char* names[8] = {};
      ffxQueryDescGetVersions q{};
      q.header.type = FFX_API_QUERY_DESC_TYPE_GET_VERSIONS;
      q.createDescType = type;
      q.device = nullptr;
      q.outputCount = &count;
      q.versionIds = ids;
      q.versionNames = names;
      auto r = query(nullptr, &q.header);
      std::printf("  %-18s", label);
      if (r != FFX_API_RETURN_OK) {
        std::printf(" version query failed (%u)\n", unsigned(r));
        continue;
      }
      for (uint64_t i = 0; i < count; ++i) std::printf(" %s", names[i]);
      std::printf("\n");
    }
    ffxCreateBackendVKDesc backend{};
    backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backend.vkDevice = device;
    backend.vkPhysicalDevice = pd;
    backend.vkDeviceProcAddr = LoggingDeviceProcAddr;
    ffxCreateContextDescUpscale up{};
    up.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    up.header.pNext = &backend.header;
    up.maxRenderSize = {1280, 720};
    up.maxUpscaleSize = {3840, 2160};
    up.fpMessage = FfxMessage;
    ffxContext ctx = nullptr;
    auto r = create(&ctx, &up.header, nullptr);
    std::printf("  upscaler context 720p->4K: %s (%u)\n", r == FFX_API_RETURN_OK ? "created" : "failed",
                unsigned(r));
    if (r == FFX_API_RETURN_OK) destroy(&ctx, nullptr);
  } else {
    std::printf("  amd_fidelityfx_vk.dll not found (--ffx-dir)\n");
  }

  InstanceProc<PFN_vkDestroyDevice>(instance, "vkDestroyDevice")(device, nullptr);
  InstanceProc<PFN_vkDestroyInstance>(instance, "vkDestroyInstance")(instance, nullptr);
  return 0;
}
