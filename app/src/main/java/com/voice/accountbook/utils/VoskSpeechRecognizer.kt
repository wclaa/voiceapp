package com.voice.accountbook.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线语音识别工具类
 * 基于Vosk-Android实现全离线语音识别
 */
class VoskSpeechRecognizer private constructor(private val context: Context) {

    // 日志标签
    private val TAG = "VoskSpeechRecognizer"

    // 音频参数
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // 模型和识别器
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    // 录音相关
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recognitionThread: Thread? = null
    // 用于存储识别结果
    private val sb = StringBuilder()

    // 线程池
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 回调接口
    private var callback: RecognitionCallback? = null

    // 修改：添加初始化状态控制
    private val isInitializing = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // 修改：模型文件夹名称常量
    private val MODEL_FOLDER_NAME = "vosk-model"

    /**
     * 识别回调接口
     */
    interface RecognitionCallback {
        /**
         * 模型初始化成功回调
         */
        fun onInitSuccess()

        /**
         * 模型初始化失败回调
         * @param error 错误信息
         */
        fun onInitFail(error: String)

        /**
         * 实时识别中回调
         * @param text 当前识别文本
         */
        fun onRecognizing(text: String)

        /**
         * 识别完成回调
         * @param text 最终完整文本
         */
        fun onRecognizeComplete(text: String)

        /**
         * 识别出错回调
         * @param error 错误信息
         */
        fun onError(error: String)

        /**
         * 录音权限未授予回调
         */
        fun onPermissionDenied()
    }

    /**
     * 初始化语音识别模型
     * @param callback 回调接口
     */
    fun initModel(callback: RecognitionCallback) {
        this.callback = callback

        // 修改：防止重复初始化
        if (isInitializing.get()) {
            Log.d(TAG, "模型正在初始化中，跳过重复调用")
            // 修复：去掉模型加载中提前触发的onInitFail，避免假失败提示
            return
        }

        if (isInitialized.get()) {
            Log.d(TAG, "模型已初始化成功，直接返回")
            mainHandler.post {
                callback.onInitSuccess()
            }
            return
        }

        if (!isInitializing.compareAndSet(false, true)) {
            Log.d(TAG, "模型初始化已被其他线程启动，跳过")
            // 修复：去掉模型加载中提前触发的onInitFail，避免假失败提示
            return
        }

        executor.execute {
            var initSuccess = false
            try {
                // 修改：校验JNI库加载
                if (!checkJNILoaded()) {
                    Log.e(TAG, "Vosk JNI库加载失败")
                    mainHandler.post {
                        callback.onInitFail("Vosk JNI库加载失败")
                    }
                    return@execute
                }

                // 复制模型文件到应用数据目录
                val modelPath = copyModelToDataDir()
                if (modelPath == null) {
                    Log.e(TAG, "模型文件复制失败")
                    mainHandler.post {
                        callback.onInitFail("模型文件复制失败")
                    }
                    return@execute
                }

                // 修改：校验模型文件完整性
                val validateResult = validateModelFiles(modelPath)
                if (!validateResult.first) {
                    Log.e(TAG, "模型文件不完整: ${validateResult.second}")
                    mainHandler.post {
                        callback.onInitFail("模型文件不完整: ${validateResult.second}")
                    }
                    return@execute
                }

                // 初始化模型
                Log.d(TAG, "开始初始化模型: $modelPath")
                model = Model(modelPath)
                Log.d(TAG, "模型初始化成功")
                // 初始化识别器
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                Log.d(TAG, "识别器初始化成功")

                // 标记初始化成功
                isInitialized.set(true)
                initSuccess = true

                mainHandler.post {
                    callback.onInitSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型初始化失败", e)
                mainHandler.post {
                    callback.onInitFail("模型初始化失败: ${e.message}")
                }
            } finally {
                // 重置初始化状态
                isInitializing.set(false)
                // 修复：确保即使在异常情况下，初始化状态也被正确重置
                if (!initSuccess) {
                    isInitialized.set(false)
                }
            }
        }
    }

    /**
     * 开始录音和识别
     */
    fun startRecording() {
        // 检查权限
        if (!checkRecordingPermission()) {
            callback?.onPermissionDenied()
            return
        }

        // 检查模型是否初始化
        if (recognizer == null) {
            callback?.onError("模型未初始化")
            return
        }

        executor.execute {
            try {
                // 计算最小缓冲区大小
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )

                // 初始化AudioRecord
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                // 开始录音
                audioRecord?.startRecording()
                isRecording = true

                // 创建缓冲区
                val buffer = ByteArray(bufferSize)
                // 重置StringBuilder
                sb.setLength(0)

                // 识别线程
                recognitionThread = Thread {
                    while (isRecording) {
                        try {
                            // 读取音频数据
                            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (readSize > 0) {
                                // 喂给识别器
                                if (recognizer?.acceptWaveForm(buffer, readSize) == true) {
                                    val result = recognizer?.result
                                    result?.let {
                                        try {
                                            // 解析JSON，获取text字段
                                            val jsonObject = JSONObject(it)
                                            val text = jsonObject.getString("text")
                                            // 重置StringBuilder，避免重复累积
                                            sb.setLength(0)
                                            sb.append(text)
                                            mainHandler.post {
                                                callback?.onRecognizing(sb.toString())
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "解析结果失败", e)
                                        }
                                    }
                                } else {
                                    val partialResult = recognizer?.partialResult
                                    partialResult?.let {
                                        try {
                                            // 解析JSON，获取partial字段
                                            val jsonObject = JSONObject(it)
                                            val text = jsonObject.getString("partial")
                                            // 直接使用部分结果，不累积，避免重复
                                            mainHandler.post {
                                                callback?.onRecognizing(text)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "解析部分结果失败", e)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "识别过程出错", e)
                            mainHandler.post {
                                callback?.onError("识别过程出错: ${e.message}")
                            }
                            stopRecording()
                            break
                        }
                    }
                }

                // 启动识别线程
                recognitionThread?.start()
            } catch (e: Exception) {
                Log.e(TAG, "开始录音失败", e)
                mainHandler.post {
                    callback?.onError("开始录音失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 停止录音和识别
     */
    fun stopRecording() {
        executor.execute {
            try {
                isRecording = false

                // 等待识别线程结束
                recognitionThread?.join(1000)

                // 停止录音
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "停止录音失败", e)
                }
                audioRecord?.release()
                audioRecord = null

                // 获取最终结果
                val finalResult = recognizer?.finalResult
                var finalText = ""
                finalResult?.let {
                    try {
                        // 修改：先解析JSON，再获取text字段，解决Unresolved reference报错
                        val jsonObject = JSONObject(it)
                        finalText = jsonObject.getString("text")
                    } catch (e: Exception) {
                        Log.e(TAG, "解析最终结果失败", e)
                    }
                }

                // 如果最终结果为空，使用之前保存的结果
                if (finalText.isEmpty() && sb.isNotEmpty()) {
                    finalText = sb.toString()
                }

                mainHandler.post {
                    callback?.onRecognizeComplete(finalText)
                }

                // 重置识别器
                recognizer?.reset()
            } catch (e: Exception) {
                Log.e(TAG, "停止录音失败", e)
                mainHandler.post {
                    callback?.onError("停止录音失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        executor.execute {
            try {
                // 停止录音
                isRecording = false
                recognitionThread?.join(1000)
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "停止录音失败", e)
                }
                audioRecord?.release()
                audioRecord = null

                // 释放识别器和模型
                recognizer?.close()
                recognizer = null
                model?.close()
                model = null

                // 重置初始化状态
                isInitialized.set(false)
                isInitializing.set(false)

                // 关闭线程池
                executor.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "释放资源失败", e)
            } finally {
                // 确保所有资源被置为null
                audioRecord = null
                recognizer = null
                model = null
                recognitionThread = null
                // 修改：清空单例实例，确保APP重启后能重新创建实例
                synchronized(VoskSpeechRecognizer::class.java) {
                    instance = null
                }
            }
        }
    }

    /**
     * 检查录音权限
     * @return 是否有权限
     */
    private fun checkRecordingPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 校验JNI库是否加载
     * @return JNI库是否加载成功
     */
    private fun checkJNILoaded(): Boolean {
        try {
            // 尝试加载Vosk类，验证JNI库是否可用
            Class.forName("org.vosk.Model")
            Log.d(TAG, "Vosk JNI库加载成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk JNI库加载失败", e)
            return false
        }
    }

    /**
     * 复制模型文件到应用数据目录
     * @return 模型文件路径
     */
    private fun copyModelToDataDir(): String? {
        try {
            // 修改：检查assets目录里是否存在目标模型文件夹
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_FOLDER_NAME)) {
                Log.e(TAG, "assets目录未找到Vosk模型文件夹: $MODEL_FOLDER_NAME")
                return null
            }

            // 修改：校验源文件完整性
            if (!checkSourceModelIntegrity()) {
                Log.e(TAG, "assets模型源文件缺少核心配置文件")
                return null
            }

            val modelDir = File(context.filesDir, MODEL_FOLDER_NAME)
            
            // 修改：检查模型是否需要更新
            if (needUpdateModel(modelDir)) {
                Log.d(TAG, "模型需要更新，删除旧模型")
                deleteDirectory(modelDir)
            }
            
            if (!modelDir.exists()) {
                modelDir.mkdirs()
                Log.d(TAG, "创建模型目录: ${modelDir.absolutePath}")
            }

            // 递归复制模型文件
            copyAssetsRecursively(MODEL_FOLDER_NAME, modelDir)

            // 修改：输出复制后的目录结构
            Log.d(TAG, "复制完成，目标目录结构:")
            printDirectoryStructure(modelDir, 0)

            // 验证模型文件是否复制成功
            if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                Log.e(TAG, "模型文件复制失败，目标目录为空")
                return null
            }

            Log.d(TAG, "模型文件复制成功: ${modelDir.absolutePath}")
            return modelDir.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "复制模型文件失败", e)
            return null
        }
    }

    /**
     * 校验源文件完整性
     * @return 源文件是否完整
     */
    private fun checkSourceModelIntegrity(): Boolean {
        try {
            // 检查conf目录是否存在
            val confAssets = context.assets.list("$MODEL_FOLDER_NAME/conf")
            if (confAssets == null || confAssets.isEmpty()) {
                Log.e(TAG, "assets模型源文件缺少conf目录")
                return false
            }
            
            // 检查model.conf文件是否存在
            val hasModelConf = confAssets.contains("model.conf")
            if (!hasModelConf) {
                Log.e(TAG, "assets模型源文件缺少核心配置文件: conf/model.conf")
                return false
            }
            
            Log.d(TAG, "assets模型源文件完整性校验通过")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "校验源文件完整性失败", e)
            return false
        }
    }

    /**
     * 校验模型文件完整性
     * @param modelPath 模型路径
     * @return Pair<是否完整, 错误信息>
     */
    private fun validateModelFiles(modelPath: String): Pair<Boolean, String> {
        val modelDir = File(modelPath)
        
        // 检查核心配置文件是否存在
        val modelConfFile = File(modelDir, "conf/model.conf")
        if (!modelConfFile.exists()) {
            val errorMsg = "缺少核心配置文件: conf/model.conf"
            Log.e(TAG, errorMsg)
            // 输出当前目录结构
            Log.e(TAG, "当前目录结构:")
            printDirectoryStructure(modelDir, 0)
            return Pair(false, errorMsg)
        } else if (!modelConfFile.isFile) {
            val errorMsg = "conf/model.conf被创建为目录，不是文件"
            Log.e(TAG, errorMsg)
            // 输出当前目录结构
            Log.e(TAG, "当前目录结构:")
            printDirectoryStructure(modelDir, 0)
            return Pair(false, errorMsg)
        }
        
        Log.d(TAG, "模型文件完整性校验通过")
        return Pair(true, "")
    }

    /**
     * 检查模型是否需要更新
     * @param modelDir 模型目录
     * @return 是否需要更新
     */
    private fun needUpdateModel(modelDir: File): Boolean {
        // 检查目录是否存在
        if (!modelDir.exists()) {
            Log.d(TAG, "模型目录不存在，需要更新")
            return true
        }
        
        // 检查目录是否为空
        if (modelDir.listFiles().isNullOrEmpty()) {
            Log.d(TAG, "模型目录为空，需要更新")
            return true
        }
        
        // 检查模型完整性
        val validateResult = validateModelFiles(modelDir.absolutePath)
        if (!validateResult.first) {
            Log.d(TAG, "模型文件不完整，需要更新: ${validateResult.second}")
            return true
        }
        
        // 可以在这里添加版本检查逻辑
        // 例如，读取assets中的版本文件与本地版本文件比较
        
        Log.d(TAG, "模型文件完整，不需要更新")
        return false
    }

    /**
     * 递归删除目录
     * @param directory 要删除的目录
     */
    private fun deleteDirectory(directory: File) {
        if (directory.exists()) {
            directory.listFiles()?.forEach {
                if (it.isDirectory) {
                    deleteDirectory(it)
                } else {
                    it.delete()
                }
            }
            directory.delete()
        }
    }

    /**
     * 递归复制assets目录到目标目录
     * @param assetsPath assets中的相对路径
     * @param targetDir 目标目录
     */
    private fun copyAssetsRecursively(assetsPath: String, targetDir: File) {
        try {
            val assets = context.assets.list(assetsPath)
            if (assets == null || assets.isEmpty()) {
                // 单个文件
                val inputStream = context.assets.open(assetsPath)
                // 修改：修复路径拼接，确保文件正确写入到目标目录
                val relativePath = assetsPath.substringAfter("$MODEL_FOLDER_NAME/")
                val outputFile = File(targetDir, relativePath)
                // 确保父目录存在
                outputFile.parentFile?.mkdirs()
                val outputStream = outputFile.outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                Log.d(TAG, "复制文件: $assetsPath -> ${outputFile.absolutePath}")
            } else {
                // 目录
                for (asset in assets) {
                    val newAssetsPath = if (assetsPath.isEmpty()) asset else "$assetsPath/$asset"
                    // 对于目录，我们需要递归处理，但不需要创建新的目标目录
                    // 因为文件复制时会自动创建父目录
                    copyAssetsRecursively(newAssetsPath, targetDir)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "递归复制assets失败: $assetsPath", e)
            throw e
        }
    }

    /**
     * 输出目录结构
     * @param directory 目录
     * @param level 层级
     */
    private fun printDirectoryStructure(directory: File, level: Int) {
        val indent = "  ".repeat(level)
        if (directory.isDirectory) {
            Log.d(TAG, "$indent${directory.name}/")
            directory.listFiles()?.forEach {
                printDirectoryStructure(it, level + 1)
            }
        } else {
            Log.d(TAG, "$indent${directory.name} (文件)")
        }
    }

    companion object {
        // 单例实例
        private var instance: VoskSpeechRecognizer? = null

        /**
         * 获取单例实例
         * @param context 上下文
         * @return VoskSpeechRecognizer实例
         */
        fun getInstance(context: Context): VoskSpeechRecognizer {
            synchronized(VoskSpeechRecognizer::class.java) {
                if (instance == null) {
                    instance = VoskSpeechRecognizer(context.applicationContext)
                }
                return instance!!
            }
        }
    }
}