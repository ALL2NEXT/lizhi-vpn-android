RUNNER_PATH := $(abspath $(call my-dir))
LOCAL_PATH := $(RUNNER_PATH)
HEV_ROOT ?= $(RUNNER_PATH)/../../../hev-socks5-tunnel

# Reuse the upstream Android builds for yaml, lwIP and the task scheduler.
include $(HEV_ROOT)/third-part/yaml/Android.mk
include $(HEV_ROOT)/third-part/lwip/Android.mk
include $(HEV_ROOT)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(RUNNER_PATH)
HEV_SRCDIR := $(HEV_ROOT)/src
SRCDIR := $(HEV_SRCDIR)
include $(HEV_ROOT)/build.mk

include $(CLEAR_VARS)
LOCAL_MODULE := hev-runner
LOCAL_SRC_FILES := $(filter-out %/hev-jni.c,$(SRCFILES)) runner.c
LOCAL_C_INCLUDES := \
    $(HEV_ROOT)/src \
    $(HEV_ROOT)/src/misc \
    $(HEV_ROOT)/src/core/include \
    $(HEV_ROOT)/third-part/yaml/include \
    $(HEV_ROOT)/third-part/lwip/src/include \
    $(HEV_ROOT)/third-part/lwip/src/ports/include \
    $(HEV_ROOT)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DENABLE_LIBRARY -DANDROID -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
LOCAL_CFLAGS += -DHEV_RUNNER_VERSION=\"2.16.0\"
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := libyaml liblwip libhev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
