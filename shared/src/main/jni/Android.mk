LOCAL_PATH := $(call my-dir)

# Mazda release scripts select their module's APAC sources; unselected builds keep the stub.
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_apac
LOCAL_SRC_FILES := apac_jni.cpp
LOCAL_CPPFLAGS := -std=c++11 -O2 -fno-exceptions -fno-rtti
ifneq ($(DIPLAY_APAC_SOURCE),)
LOCAL_CPPFLAGS += -DDIPLAY_EXTERNAL_APAC
LOCAL_C_INCLUDES := $(DIPLAY_APAC_SOURCE)/include
LOCAL_SRC_FILES += $(wildcard $(DIPLAY_APAC_SOURCE)/src/*.cpp)
endif
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
