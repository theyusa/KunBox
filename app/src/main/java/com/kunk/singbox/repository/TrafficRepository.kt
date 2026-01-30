package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class NodeTrafficStats(
    val nodeId: String,
    var upload: Long = 0,
    var download: Long = 0,
    var lastUpdated: Long = 0,
    var nodeName: String? = null
)

data class DailyTrafficRecord(
    val dateKey: String,
    val nodeStats: MutableMap<String, NodeTrafficStats> = mutableMapOf()
)

enum class TrafficPeriod {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    ALL_TIME
}

data class TrafficSummary(
    val totalUpload: Long,
    val totalDownload: Long,
    val nodeStats: List<NodeTrafficStats>,
    val period: TrafficPeriod
)

/**
 * 流量统计仓库
 * 负责持久化存储节点流量数据，支持按时间维度查询
 */
class TrafficRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TrafficRepository"
        private const val FILE_NAME = "traffic_stats.json"
        private const val DAILY_FILE_NAME = "traffic_daily.json"
        private val TRAFFIC_STATS_MAP_TYPE = object : TypeToken<Map<String, NodeTrafficStats>>() {}.type
        private val DAILY_RECORDS_TYPE = object : TypeToken<Map<String, DailyTrafficRecord>>() {}.type

        @Volatile
        private var instance: TrafficRepository? = null

        fun getInstance(context: Context): TrafficRepository {
            return instance ?: synchronized(this) {
                instance ?: TrafficRepository(context.applicationContext).also { instance = it }
            }
        }

        private fun getTodayKey(): String {
            val cal = Calendar.getInstance()
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }
    }

    private val gson = Gson()
    private val trafficMap = ConcurrentHashMap<String, NodeTrafficStats>()
    private val dailyRecords = ConcurrentHashMap<String, DailyTrafficRecord>()
    private val statsFile: File get() = File(context.filesDir, FILE_NAME)
    private val dailyFile: File get() = File(context.filesDir, DAILY_FILE_NAME)
    private var lastSaveTime = 0L

    init {
        loadStats()
        loadDailyRecords()
        checkMonthlyReset()
        cleanOldRecords()
    }

    private fun loadStats() {
        if (!statsFile.exists()) return
        try {
            val json = statsFile.readText()
            val loaded: Map<String, NodeTrafficStats>? = gson.fromJson(json, TRAFFIC_STATS_MAP_TYPE)
            if (loaded != null) {
                trafficMap.putAll(loaded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load traffic stats", e)
        }
    }

    private fun loadDailyRecords() {
        if (!dailyFile.exists()) return
        try {
            val json = dailyFile.readText()
            val loaded: Map<String, DailyTrafficRecord>? = gson.fromJson(json, DAILY_RECORDS_TYPE)
            if (loaded != null) {
                dailyRecords.putAll(loaded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load daily records", e)
        }
    }

    fun saveStats() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 10000) return

        try {
            val json = gson.toJson(trafficMap)
            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            tmpFile.writeText(json)
            if (tmpFile.renameTo(statsFile)) {
                lastSaveTime = now
            } else {
                tmpFile.copyTo(statsFile, overwrite = true)
                tmpFile.delete()
            }

            val dailyJson = gson.toJson(dailyRecords)
            val dailyTmpFile = File(context.filesDir, "$DAILY_FILE_NAME.tmp")
            dailyTmpFile.writeText(dailyJson)
            if (!dailyTmpFile.renameTo(dailyFile)) {
                dailyTmpFile.copyTo(dailyFile, overwrite = true)
                dailyTmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save traffic stats", e)
        }
    }

    private fun checkMonthlyReset() {
        val prefs = context.getSharedPreferences("traffic_prefs", Context.MODE_PRIVATE)
        val lastMonth = prefs.getInt("last_month", -1)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)

        if (lastMonth != -1 && lastMonth != currentMonth) {
            Log.i(TAG, "New month detected ($lastMonth -> $currentMonth), resetting traffic stats")
            trafficMap.clear()
            saveStats()
        }

        if (lastMonth != currentMonth) {
            prefs.edit().putInt("last_month", currentMonth).apply()
        }
    }

    private fun cleanOldRecords() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -60)
        val cutoffTime = cal.timeInMillis

        val keysToRemove = dailyRecords.keys.filter { dateKey ->
            try {
                val parts = dateKey.split("-")
                if (parts.size == 3) {
                    val recordCal = Calendar.getInstance()
                    recordCal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    recordCal.timeInMillis < cutoffTime
                } else false
            } catch (_: Exception) {
                false
            }
        }

        keysToRemove.forEach { dailyRecords.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned ${keysToRemove.size} old daily records")
        }
    }

    fun addTraffic(nodeId: String, uploadDiff: Long, downloadDiff: Long, nodeName: String? = null) {
        if (uploadDiff <= 0 && downloadDiff <= 0) return

        val stats = trafficMap.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
        stats.upload += uploadDiff
        stats.download += downloadDiff
        stats.lastUpdated = System.currentTimeMillis()
        if (!nodeName.isNullOrBlank()) {
            stats.nodeName = nodeName
        }

        val todayKey = getTodayKey()
        val dailyRecord = dailyRecords.getOrPut(todayKey) { DailyTrafficRecord(todayKey) }
        val dailyStats = dailyRecord.nodeStats.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
        dailyStats.upload += uploadDiff
        dailyStats.download += downloadDiff
        dailyStats.lastUpdated = System.currentTimeMillis()
        if (!nodeName.isNullOrBlank()) {
            dailyStats.nodeName = nodeName
        }
    }

    fun getStats(nodeId: String): NodeTrafficStats? {
        return trafficMap[nodeId]
    }

    fun getMonthlyTotal(nodeId: String): Long {
        val stats = trafficMap[nodeId] ?: return 0
        return stats.upload + stats.download
    }

    fun getAllNodeStats(): List<NodeTrafficStats> {
        return trafficMap.values.toList().sortedByDescending { it.upload + it.download }
    }

    fun getTotalTraffic(): Pair<Long, Long> {
        var totalUpload = 0L
        var totalDownload = 0L
        trafficMap.values.forEach {
            totalUpload += it.upload
            totalDownload += it.download
        }
        return Pair(totalUpload, totalDownload)
    }

    fun getTrafficSummary(period: TrafficPeriod): TrafficSummary {
        return when (period) {
            TrafficPeriod.TODAY -> getTodayTraffic()
            TrafficPeriod.THIS_WEEK -> getWeekTraffic()
            TrafficPeriod.THIS_MONTH -> getMonthTraffic()
            TrafficPeriod.ALL_TIME -> getAllTimeTraffic()
        }
    }

    private fun getTodayTraffic(): TrafficSummary {
        val todayKey = getTodayKey()
        val record = dailyRecords[todayKey]

        if (record == null) {
            return TrafficSummary(0, 0, emptyList(), TrafficPeriod.TODAY)
        }

        var totalUp = 0L
        var totalDown = 0L
        record.nodeStats.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = record.nodeStats.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.TODAY)
    }

    @Suppress("NestedBlockDepth")
    private fun getWeekTraffic(): TrafficSummary {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekStart = cal.timeInMillis

        val aggregated = mutableMapOf<String, NodeTrafficStats>()

        dailyRecords.values.forEach { record ->
            try {
                val parts = record.dateKey.split("-")
                if (parts.size == 3) {
                    val recordCal = Calendar.getInstance()
                    recordCal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    if (recordCal.timeInMillis >= weekStart) {
                        record.nodeStats.forEach { (nodeId, stats) ->
                            val existing = aggregated.getOrPut(nodeId) { NodeTrafficStats(nodeId) }
                            existing.upload += stats.upload
                            existing.download += stats.download
                            if (stats.lastUpdated > existing.lastUpdated) {
                                existing.lastUpdated = stats.lastUpdated
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        var totalUp = 0L
        var totalDown = 0L
        aggregated.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = aggregated.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.THIS_WEEK)
    }

    private fun getMonthTraffic(): TrafficSummary {
        var totalUp = 0L
        var totalDown = 0L
        trafficMap.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = trafficMap.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.THIS_MONTH)
    }

    private fun getAllTimeTraffic(): TrafficSummary {
        val aggregated = mutableMapOf<String, NodeTrafficStats>()

        trafficMap.forEach { (nodeId, stats) ->
            aggregated[nodeId] = NodeTrafficStats(nodeId, stats.upload, stats.download, stats.lastUpdated)
        }

        dailyRecords.values.forEach { record ->
            record.nodeStats.forEach { (nodeId, stats) ->
                if (!aggregated.containsKey(nodeId)) {
                    aggregated[nodeId] = NodeTrafficStats(nodeId, stats.upload, stats.download, stats.lastUpdated)
                }
            }
        }

        var totalUp = 0L
        var totalDown = 0L
        aggregated.values.forEach {
            totalUp += it.upload
            totalDown += it.download
        }

        val nodeList = aggregated.values.toList().sortedByDescending { it.upload + it.download }
        return TrafficSummary(totalUp, totalDown, nodeList, TrafficPeriod.ALL_TIME)
    }

    fun getTopNodes(period: TrafficPeriod, limit: Int = 10): List<NodeTrafficStats> {
        return getTrafficSummary(period).nodeStats.take(limit)
    }

    fun getNodeTrafficPercentages(period: TrafficPeriod): List<Pair<NodeTrafficStats, Float>> {
        val summary = getTrafficSummary(period)
        val total = summary.totalUpload + summary.totalDownload
        if (total == 0L) return emptyList()

        return summary.nodeStats.map { stats ->
            val nodeTotal = stats.upload + stats.download
            val percentage = (nodeTotal.toFloat() / total.toFloat()) * 100f
            Pair(stats, percentage)
        }
    }

    fun forceSave() {
        lastSaveTime = 0L
        saveStats()
    }

    fun clearAllStats() {
        trafficMap.clear()
        dailyRecords.clear()
        forceSave()
        Log.i(TAG, "All traffic stats cleared")
    }
}
