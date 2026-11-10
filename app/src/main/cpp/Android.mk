LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := atmo_vulkan
LOCAL_SRC_FILES := \
    vulkan_one_pass_engine.cpp \
    vulkan_color_fill_jni.cpp \
    vulkan_halftone_jni.cpp \
    vulkan_vhs_jni.cpp \
    vulkan_zoom_jni.cpp \
    vulkan_glass_jni.cpp \
    vulkan_canvas_sketch_jni.cpp \
    vulkan_atmosphere_jni.cpp \
    vulkan_frosted_jni.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Wno-missing-field-initializers
LOCAL_LDLIBS := -landroid -ljnigraphics -llog -lvulkan
include $(BUILD_SHARED_LIBRARY)
