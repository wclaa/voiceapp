package com.voice.accountbook.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限申请工具类
 * 基于Activity Result API实现，适配Android 7.0-14
 */
class PermissionManager private constructor() {

    /**
     * 权限回调接口
     */
    interface PermissionCallback {
        /**
         * 权限授权成功
         */
        fun onPermissionGranted()

        /**
         * 用户拒绝权限
         */
        fun onPermissionDenied()

        /**
         * 用户永久拒绝权限并勾选不再提示
         */
        fun onPermissionPermanentlyDenied()
    }

    /**
     * 检查录音权限是否已授予
     * @param activity Activity实例
     * @return 是否已授予
     */
    fun checkRecordPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否应该显示权限申请理由
     * @param activity Activity实例
     * @return 是否应该显示理由
     */
    fun shouldShowPermissionRationale(activity: Activity): Boolean {
        return activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
    }

    /**
     * 跳转应用设置页面
     * @param activity Activity实例
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", activity.packageName, null)
        intent.data = uri
        activity.startActivity(intent)
    }

    companion object {
        // 单例实例
        private var instance: PermissionManager? = null

        /**
         * 获取单例实例
         * @return PermissionManager实例
         */
        fun getInstance(): PermissionManager {
            if (instance == null) {
                instance = PermissionManager()
            }
            return instance!!
        }
    }
}