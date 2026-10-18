"""Texture packs load on the Vulkan renderer too (#518).

The dump, lookup and DDS code is shared by both texture caches, its settings
exist on every platform, and the Vulkan cache replaces a texture before the
guest load but never for resolve output.
"""

import pathlib
import unittest

SDK = pathlib.Path(__file__).resolve().parents[2] / "thirdparty/shiftglue-sdk"


def read(path):
    return (SDK / path).read_text(encoding="utf-8")


class TextureReplacementTests(unittest.TestCase):
    def test_the_settings_live_in_the_shared_module(self):
        shared = read("src/graphics/pipeline/texture/replacement.cpp")
        for name in ("texture_dump_dir", "texture_replacement_dirs", "texture_replacement_reload"):
            self.assertIn(f"({name},", shared)
            self.assertNotIn(f"({name},", read("src/graphics/d3d12/texture_cache.cpp"))
        self.assertIn("pipeline/texture/replacement.cpp", read("src/graphics/CMakeLists.txt"))
        # Portable file access: no _wfopen.
        self.assertNotIn("_wfopen", shared)

    def test_vulkan_replaces_before_the_guest_load(self):
        cache = read("src/graphics/vulkan/texture_cache.cpp")
        load = cache[cache.index("bool VulkanTextureCache::LoadTextureDataFromResidentMemoryUntimed("):]
        hook = load.index("TryLoadTextureReplacement(vulkan_texture)")
        self.assertLess(load.index("if (loading_resolve_sourced()) {"), hook)
        self.assertLess(hook, load.index("// Get the pipeline."))

    def test_vulkan_reloads_and_retires_old_images(self):
        cache = read("src/graphics/vulkan/texture_cache.cpp")
        frame = cache[cache.index("void VulkanTextureCache::BeginFrame() {"):]
        self.assertIn("texture_replacement::ReloadRequested()", frame[:frame.index("\n}\n")])
        completed = cache[cache.index("void VulkanTextureCache::CompletedSubmissionUpdated("):]
        self.assertIn("DestroyRetiredReplacementResources(completed_submission_index);",
                      completed[:completed.index("\n}\n")])
        self.assertIn("DestroyRetiredReplacementResources(UINT64_MAX);", cache)


if __name__ == "__main__":
    unittest.main()
