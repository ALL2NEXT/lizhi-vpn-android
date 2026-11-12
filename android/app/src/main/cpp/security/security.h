#pragma once

#include <jni.h>

#include <string>
#include <vector>

namespace chimera {
namespace security {

std::vector<std::string> scan(JNIEnv* env, jobject context);
jstring reportScreenshotCapture(JNIEnv* env);
void applyScreenshotProtection(JNIEnv* env, jobject activity);
void applyCaptureProtection(JNIEnv* env);
void reportNetworkFinding(JNIEnv* env, jstring finding);
void addFinding(std::vector<std::string>* findings, const std::string& code);
void fatalFinding(std::vector<std::string>* findings, const char* code);
void terminateOnFinding(const char* code);
void reportFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings);
void detectCapture(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectXposed(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectLsposed(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectFrida(std::vector<std::string>* findings);
void detectRoot(std::vector<std::string>* findings);
void detectLspatch(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectNpatch(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectSignature(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectBlacklist(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectAdb(JNIEnv* env, jobject context, std::vector<std::string>* findings);
void detectDeveloperMode(JNIEnv* env, jobject context, std::vector<std::string>* findings);

}  // security 命名空间结束
}  // chimera 命名空间结束
