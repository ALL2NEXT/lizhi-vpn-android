#pragma once

#include <jni.h>

#include <string>
#include <vector>

namespace chimera {
namespace security {

std::vector<std::string> scan(JNIEnv* env, jobject context);
jstring reportScreenshotCapture(JNIEnv* env);
void detectCapture(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectXposed(std::vector<std::string>* findings);
void detectLsposed(std::vector<std::string>* findings);
void detectFrida(std::vector<std::string>* findings);
void detectSignature(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectBlacklist(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectAdb(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectDeveloperMode(JNIEnv* env, jobject context, std::vector<std::string>* findings);

}  // security 命名空间结束
}  // chimera 命名空间结束
