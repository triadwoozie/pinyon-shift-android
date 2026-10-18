// CS-0.5. Whole-allocation placed mapping, checked by a compute shader.
#include <algorithm>
#include <fstream>
#ifndef _WIN32
#include <sys/mman.h>
#endif

static bool placed_map(PFN_vkGetInstanceProcAddr gipa, VkInstance instance,
                       VkPhysicalDevice pd, const VkPhysicalDeviceProperties& properties,
                       const VkPhysicalDeviceMapMemoryPlacedFeaturesEXT& supported,
                       VkDeviceSize placed_alignment, const char* shader_path) {
#if defined(_WIN32) || defined(__APPLE__)
  throw std::runtime_error("placed-map reservation test implemented for Linux/Android only");
#else
#define IPROC(name) auto name = reinterpret_cast<PFN_##name>(gipa(instance, #name)); if (!name) throw std::runtime_error(#name)
  IPROC(vkGetPhysicalDeviceQueueFamilyProperties);
  IPROC(vkGetPhysicalDeviceMemoryProperties);
  IPROC(vkCreateDevice);
  IPROC(vkGetDeviceProcAddr);
  uint32_t queue_count = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(pd, &queue_count, nullptr);
  std::vector<VkQueueFamilyProperties> queues(queue_count);
  vkGetPhysicalDeviceQueueFamilyProperties(pd, &queue_count, queues.data());
  uint32_t family = 0;
  while (family < queue_count && !(queues[family].queueFlags & VK_QUEUE_COMPUTE_BIT)) ++family;
  if (family == queue_count) throw std::runtime_error("no compute queue");
  float priority = 1;
  VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = family; qi.queueCount = 1; qi.pQueuePriorities = &priority;
  VkPhysicalDeviceMapMemoryPlacedFeaturesEXT features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
  features.memoryMapPlaced = VK_TRUE;
  features.memoryUnmapReserve = supported.memoryUnmapReserve;
  const char* extensions[] = {VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME, VK_KHR_MAP_MEMORY_2_EXTENSION_NAME};
  VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  di.pNext = &features; di.queueCreateInfoCount = 1; di.pQueueCreateInfos = &qi;
  di.enabledExtensionCount = 2; di.ppEnabledExtensionNames = extensions;
  VkDevice device;
  check(vkCreateDevice(pd, &di, nullptr, &device), "placed vkCreateDevice");
#define DPROC(name) auto name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name)); if (!name) throw std::runtime_error(#name)
  DPROC(vkDestroyDevice); DPROC(vkGetDeviceQueue);
  DPROC(vkCreateBuffer); DPROC(vkDestroyBuffer); DPROC(vkGetBufferMemoryRequirements);
  DPROC(vkAllocateMemory); DPROC(vkFreeMemory); DPROC(vkBindBufferMemory);
  DPROC(vkMapMemory); DPROC(vkUnmapMemory); DPROC(vkMapMemory2KHR); DPROC(vkUnmapMemory2KHR);
  DPROC(vkCreateShaderModule); DPROC(vkDestroyShaderModule);
  DPROC(vkCreateDescriptorSetLayout); DPROC(vkDestroyDescriptorSetLayout);
  DPROC(vkCreatePipelineLayout); DPROC(vkDestroyPipelineLayout);
  DPROC(vkCreateComputePipelines); DPROC(vkDestroyPipeline);
  DPROC(vkCreateDescriptorPool); DPROC(vkDestroyDescriptorPool);
  DPROC(vkAllocateDescriptorSets); DPROC(vkUpdateDescriptorSets);
  DPROC(vkCreateCommandPool); DPROC(vkDestroyCommandPool); DPROC(vkAllocateCommandBuffers);
  DPROC(vkBeginCommandBuffer); DPROC(vkEndCommandBuffer); DPROC(vkCmdPipelineBarrier);
  DPROC(vkCmdBindPipeline); DPROC(vkCmdBindDescriptorSets); DPROC(vkCmdPushConstants); DPROC(vkCmdDispatch);
  DPROC(vkCreateFence); DPROC(vkDestroyFence); DPROC(vkQueueSubmit); DPROC(vkWaitForFences);
  constexpr VkDeviceSize bytes = 512ull << 20;
  VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  bi.size = bytes; bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  VkBuffer input, output;
  check(vkCreateBuffer(device, &bi, nullptr, &input), "input buffer");
  bi.size = 4;
  check(vkCreateBuffer(device, &bi, nullptr, &output), "output buffer");
  VkMemoryRequirements ir, orq;
  vkGetBufferMemoryRequirements(device, input, &ir);
  vkGetBufferMemoryRequirements(device, output, &orq);
  VkPhysicalDeviceMemoryProperties memory;
  vkGetPhysicalDeviceMemoryProperties(pd, &memory);
  auto type = [&](uint32_t bits) {
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
      constexpr auto required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
      if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & required) == required) return i;
    }
    throw std::runtime_error("no host-visible coherent storage-buffer memory type");
  };
  const uint32_t input_type = type(ir.memoryTypeBits);
  VkDeviceSize allocation_bytes = ((ir.size + placed_alignment - 1) / placed_alignment) * placed_alignment;
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ai.allocationSize = allocation_bytes; ai.memoryTypeIndex = input_type;
  VkDeviceMemory input_memory, output_memory;
  check(vkAllocateMemory(device, &ai, nullptr, &input_memory), "512 MiB allocation");
  ai.allocationSize = orq.size; ai.memoryTypeIndex = type(orq.memoryTypeBits);
  check(vkAllocateMemory(device, &ai, nullptr, &output_memory), "result allocation");
  check(vkBindBufferMemory(device, input, input_memory, 0), "bind input");
  check(vkBindBufferMemory(device, output, output_memory, 0), "bind output");
  // Never MAP_FIXED over an unowned range. Kernels ignoring NOREPLACE must
  // return exactly the requested address or the probe refuses to proceed.
  void* requested = reinterpret_cast<void*>(uintptr_t(0x200000000ull));
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
  void* reserved = mmap(requested, allocation_bytes, PROT_NONE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  if (reserved == MAP_FAILED) throw std::runtime_error("fixed address reservation failed");
  if (reserved != requested) {
    munmap(reserved, allocation_bytes);
    throw std::runtime_error("kernel ignored fixed reservation");
  }
  VkMemoryMapPlacedInfoEXT place{VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT};
  place.pPlacedAddress = reserved;
  VkMemoryMapInfoKHR mi{VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR};
  mi.pNext = &place; mi.flags = VK_MEMORY_MAP_PLACED_BIT_EXT;
  mi.memory = input_memory; mi.size = VK_WHOLE_SIZE;
  void* mapped = nullptr;
  check(vkMapMemory2KHR(device, &mi, &mapped), "vkMapMemory2KHR placed");
  if (mapped != requested) throw std::runtime_error("placed map returned wrong address");
  auto* words = static_cast<uint32_t*>(mapped);
  for (uint32_t i = 0; i < bytes / 4; ++i) words[i] = i ^ 0xA5C39E71u;
  void* result_ptr = nullptr;
  check(vkMapMemory(device, output_memory, 0, VK_WHOLE_SIZE, 0, &result_ptr), "map result");
  *static_cast<uint32_t*>(result_ptr) = 0;
  std::ifstream shader(shader_path, std::ios::binary | std::ios::ate);
  if (!shader || shader.tellg() <= 0 || uint64_t(shader.tellg()) % 4) throw std::runtime_error("invalid shader file");
  std::vector<uint32_t> code(size_t(shader.tellg()) / 4);
  shader.seekg(0); shader.read(reinterpret_cast<char*>(code.data()), code.size() * 4);
  if (!shader || code[0] != 0x07230203) throw std::runtime_error("invalid SPIR-V");
  VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  si.codeSize = code.size() * 4; si.pCode = code.data();
  VkShaderModule module;
  check(vkCreateShaderModule(device, &si, nullptr, &module), "shader module");
  VkDescriptorSetLayoutBinding bindings[2]{};
  for (uint32_t i = 0; i < 2; ++i) {
    bindings[i].binding = i; bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[i].descriptorCount = 1; bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  }
  VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  li.bindingCount = 2; li.pBindings = bindings;
  VkDescriptorSetLayout layout;
  check(vkCreateDescriptorSetLayout(device, &li, nullptr, &layout), "descriptor layout");
  VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
  VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pli.setLayoutCount = 1; pli.pSetLayouts = &layout; pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &push;
  VkPipelineLayout pipeline_layout;
  check(vkCreatePipelineLayout(device, &pli, nullptr, &pipeline_layout), "pipeline layout");
  VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
  pi.layout = pipeline_layout;
  pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
  pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT; pi.stage.module = module; pi.stage.pName = "main";
  VkPipeline pipeline;
  check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pi, nullptr, &pipeline), "compute pipeline");
  // Stay below maxStorageBufferRange; validate every word, not a sparse sample.
  VkDeviceSize chunk = std::min<VkDeviceSize>(32ull << 20, properties.limits.maxStorageBufferRange);
  chunk -= chunk % std::max<VkDeviceSize>(4, properties.limits.minStorageBufferOffsetAlignment);
  if (!chunk) throw std::runtime_error("no usable descriptor range");
  uint32_t chunks = uint32_t((bytes + chunk - 1) / chunk);
  VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2 * chunks};
  VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  dpi.maxSets = chunks; dpi.poolSizeCount = 1; dpi.pPoolSizes = &pool_size;
  VkDescriptorPool pool;
  check(vkCreateDescriptorPool(device, &dpi, nullptr, &pool), "descriptor pool");
  std::vector<VkDescriptorSetLayout> layouts(chunks, layout);
  std::vector<VkDescriptorSet> sets(chunks);
  VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  dai.descriptorPool = pool; dai.descriptorSetCount = chunks; dai.pSetLayouts = layouts.data();
  check(vkAllocateDescriptorSets(device, &dai, sets.data()), "descriptor sets");
  for (uint32_t i = 0; i < chunks; ++i) {
    VkDescriptorBufferInfo infos[] = {{input, i * chunk, std::min(chunk, bytes - i * chunk)}, {output, 0, 4}};
    VkWriteDescriptorSet writes[2]{};
    for (uint32_t j = 0; j < 2; ++j) {
      writes[j].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[j].dstSet = sets[i]; writes[j].dstBinding = j; writes[j].descriptorCount = 1;
      writes[j].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[j].pBufferInfo = &infos[j];
    }
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);
  }
  VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  cpi.queueFamilyIndex = family;
  VkCommandPool command_pool;
  check(vkCreateCommandPool(device, &cpi, nullptr, &command_pool), "command pool");
  VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  cai.commandPool = command_pool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
  VkCommandBuffer cb;
  check(vkAllocateCommandBuffers(device, &cai, &cb), "command buffer");
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  check(vkBeginCommandBuffer(cb, &begin), "begin");
  VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
  barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
  vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
  for (uint32_t i = 0; i < chunks; ++i) {
    uint32_t params[] = {uint32_t(i * chunk / 4), uint32_t(std::min(chunk, bytes - i * chunk) / 4)};
    uint32_t groups = (params[1] + 255) / 256;
    if (groups > properties.limits.maxComputeWorkGroupCount[0]) throw std::runtime_error("dispatch limit");
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &sets[i], 0, nullptr);
    vkCmdPushConstants(cb, pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), params);
    vkCmdDispatch(cb, groups, 1, 1);
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
  }
  barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
  check(vkEndCommandBuffer(cb), "end");
  VkQueue queue;
  vkGetDeviceQueue(device, family, 0, &queue);
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  VkFence fence;
  check(vkCreateFence(device, &fi, nullptr, &fence), "fence");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.commandBufferCount = 1; submit.pCommandBuffers = &cb;
  check(vkQueueSubmit(queue, 1, &submit, fence), "submit");
  check(vkWaitForFences(device, 1, &fence, VK_TRUE, 60000000000ull), "compute completion");
  uint32_t errors = *static_cast<uint32_t*>(result_ptr);
  VkMemoryUnmapInfoKHR unmap{VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO_KHR};
  unmap.memory = input_memory;
  if (supported.memoryUnmapReserve) unmap.flags = VK_MEMORY_UNMAP_RESERVE_BIT_EXT;
  check(vkUnmapMemory2KHR(device, &unmap), "unmap placed memory");
  munmap(reserved, allocation_bytes);
  vkUnmapMemory(device, output_memory);
  vkDestroyFence(device, fence, nullptr);
  vkDestroyCommandPool(device, command_pool, nullptr);
  vkDestroyDescriptorPool(device, pool, nullptr);
  vkDestroyPipeline(device, pipeline, nullptr);
  vkDestroyPipelineLayout(device, pipeline_layout, nullptr);
  vkDestroyDescriptorSetLayout(device, layout, nullptr);
  vkDestroyShaderModule(device, module, nullptr);
  vkDestroyBuffer(device, input, nullptr); vkDestroyBuffer(device, output, nullptr);
  vkFreeMemory(device, input_memory, nullptr); vkFreeMemory(device, output_memory, nullptr);
  vkDestroyDevice(device, nullptr);
  std::cout << ",\"placedCheck\":{\"bytes\":" << bytes << ",\"allocationBytes\":" << allocation_bytes
            << ",\"fixedAddress\":8589934592,\"wordsChecked\":" << bytes / 4
            << ",\"memoryType\":" << input_type << ",\"memoryFlags\":" << memory.memoryTypes[input_type].propertyFlags
            << ",\"mismatches\":" << errors << ",\"pass\":" << (errors == 0) << '}';
  return errors == 0;
#undef IPROC
#undef DPROC
#endif
}
