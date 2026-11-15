#pragma once

#include <jni.h>

#include <string>
#include <vector>

namespace chimera {
namespace security {

std::vector<std::string> scan(JNIEnv* env, jobject context);
void applyScreenshotProtection(JNIEnv* env, jobject activity, std::vector<std::string>* findings);
void addFinding(std::vector<std::string>* findings, const std::string& code);
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

}  // namespace security
}  // namespace chimera
