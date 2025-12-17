package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.ui.screens.HubRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import android.util.Log

class RuleSetViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "RuleSetViewModel"
    }
    
    private val _ruleSets = MutableStateFlow<List<HubRuleSet>>(emptyList())
    val ruleSets: StateFlow<List<HubRuleSet>> = _ruleSets.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val client = OkHttpClient()
    private val gson = Gson()

    init {
        fetchRuleSets()
    }

    fun fetchRuleSets() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "========== 开始获取规则集 ==========")
            try {
                Log.d(TAG, ">>> 获取 SagerNet 官方规则集...")
                val sagerNetRules = fetchFromSagerNet()
                Log.d(TAG, "<<< SagerNet 获取到 ${sagerNetRules.size} 个规则集")
                
                if (sagerNetRules.isEmpty()) {
                    Log.w(TAG, "在线获取失败，使用预置规则集")
                    _ruleSets.value = getBuiltInRuleSets().sortedBy { it.name }
                } else {
                    _ruleSets.value = sagerNetRules.sortedBy { it.name }
                }
                Log.d(TAG, "========== 规则集加载完成 ==========")
            } catch (e: Exception) {
                Log.e(TAG, "获取规则集失败", e)
                _error.value = "加载失败: ${e.message}"
                _ruleSets.value = getBuiltInRuleSets()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun getBuiltInRuleSets(): List<HubRuleSet> {
        val baseUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val commonRules = listOf(
            "google", "youtube", "twitter", "facebook", "instagram", "tiktok",
            "telegram", "whatsapp", "discord", "github", "microsoft", "apple",
            "amazon", "netflix", "spotify", "bilibili", "zhihu", "baidu",
            "tencent", "alibaba", "jd", "taobao", "weibo", "douyin",
            "cn", "geolocation-cn", "geolocation-!cn", "private", "ads"
        )
        return commonRules.map { name ->
            HubRuleSet(
                name = "geosite-$name",
                ruleCount = 0,
                tags = listOf("预置", "geosite"),
                description = "常用规则集",
                sourceUrl = "$baseUrl/geosite-$name.json",
                binaryUrl = "$baseUrl/geosite-$name.srs"
            )
        }
    }

    private fun fetchFromSagerNet(): List<HubRuleSet> {
        // SagerNet 的 .srs 文件在 rule-set 分支的根目录，不是主分支的子目录
        val url = "https://api.github.com/repos/SagerNet/sing-geosite/contents/?ref=rule-set"
        Log.d(TAG, "[SagerNet] 请求 URL: $url")
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KunK-SingBox-App")
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[SagerNet] HTTP 状态码: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "[SagerNet] 请求失败! 状态码=${response.code}, 响应=$errorBody")
                    return emptyList()
                }

                val json = response.body?.string() ?: "[]"
                Log.d(TAG, "[SagerNet] 响应长度: ${json.length} 字符")
                
                val type = object : TypeToken<List<GithubFile>>() {}.type
                val files: List<GithubFile> = gson.fromJson(json, type)
                Log.d(TAG, "[SagerNet] 解析到 ${files.size} 个文件")
                
                val srsFiles = files.filter { it.name.endsWith(".srs") }
                Log.d(TAG, "[SagerNet] 其中 .srs 文件 ${srsFiles.size} 个")
                
                if (srsFiles.isNotEmpty()) {
                    Log.d(TAG, "[SagerNet] 前5个文件: ${srsFiles.take(5).map { it.name }}")
                }

                srsFiles.map { file ->
                    val nameWithoutExt = file.name.substringBeforeLast(".srs")
                    HubRuleSet(
                        name = nameWithoutExt,
                        ruleCount = 0,
                        tags = listOf("官方", "geosite"),
                        description = "SagerNet 官方规则集",
                        sourceUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/${file.name.replace(".srs", ".json")}",
                        binaryUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/${file.name}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SagerNet] 发生异常: ${e.javaClass.simpleName} - ${e.message}", e)
            emptyList()
        }
    }

    data class GithubFile(
        val name: String,
        val path: String,
        val size: Long,
        val download_url: String?
    )
}
