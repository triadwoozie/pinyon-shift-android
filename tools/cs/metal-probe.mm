// CS-0.3: verify native Metal ICB allocation and per-command pipeline binding.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <cstdio>

int main() { @autoreleasepool {
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) return 1;
  NSError* error = nil;
  NSString* source = @"#include <metal_stdlib>\nusing namespace metal;\n"
      "vertex float4 vs(uint i [[vertex_id]]) { return float4(0,0,0,1); }\n"
      "fragment float4 fs() { return float4(1); }\n";
  id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
  MTLRenderPipelineDescriptor* pipelineDesc = [MTLRenderPipelineDescriptor new];
  pipelineDesc.vertexFunction = [library newFunctionWithName:@"vs"];
  pipelineDesc.fragmentFunction = [library newFunctionWithName:@"fs"];
  pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
  pipelineDesc.supportIndirectCommandBuffers = YES;
  id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
  MTLIndirectCommandBufferDescriptor* desc = [MTLIndirectCommandBufferDescriptor new];
  desc.commandTypes = MTLIndirectCommandTypeDraw | MTLIndirectCommandTypeDrawIndexed;
  desc.inheritPipelineState = NO;
  desc.inheritBuffers = NO;
  desc.maxVertexBufferBindCount = 1;
  desc.maxFragmentBufferBindCount = 1;
  id<MTLIndirectCommandBuffer> buffer = [device newIndirectCommandBufferWithDescriptor:desc maxCommandCount:2 options:MTLResourceStorageModeShared];
  if (buffer && pipeline) {
    [[buffer indirectRenderCommandAtIndex:0] setRenderPipelineState:pipeline];
    [[buffer indirectRenderCommandAtIndex:1] setRenderPipelineState:pipeline];
  }
  printf("{\"device\":\"%s\",\"renderICBAllocated\":%d,\"perCommandPipelineBound\":%d,\"executed\":false}\n",
         device.name.UTF8String, buffer != nil, buffer != nil && pipeline != nil);
  if (error) fprintf(stderr, "%s\n", error.localizedDescription.UTF8String);
  return buffer && pipeline ? 0 : 1;
} }
