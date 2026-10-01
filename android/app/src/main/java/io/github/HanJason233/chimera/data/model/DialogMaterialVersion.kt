package io.github.HanJason233.chimera.data.model

/** 应用弹窗使用的 Material 版本。 */
enum class DialogMaterialVersion(val storageName: String, val displayName: String) {
    MATERIAL_3("m3", "Material 3"),
    MATERIAL_2("m2", "Material 2");

    companion object {
        fun fromStorage(value: String?): DialogMaterialVersion =
            entries.firstOrNull { it.storageName == value } ?: MATERIAL_3
    }
}
