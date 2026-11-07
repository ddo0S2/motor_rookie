package com.hyundai.motor_rookie.intervention

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────
// 3단계 휴식 유도: 가까운 졸음쉼터 · 휴게소 찾기
//  - 위치: 시뮬레이션(고정 위치) / 실제(안드로이드 위치, 에뮬레이터는 Extended Controls > Location)
//  - 데이터: assets 폴더의 공공데이터 CSV (없으면 예시 데이터)
//      · rest_areas.csv      : 한국도로공사 전국휴게소정보표준데이터
//      · drowsy_shelters.csv : 졸음쉼터 현황
//    열 이름(위도/경도/휴게소명 등)을 자동으로 찾아서 읽는다. 인코딩(UTF-8 / CP949)도 자동.
// ─────────────────────────────────────────────

enum class RestKind(val label: String) { REST_AREA("휴게소"), SHELTER("졸음쉼터") }

data class RestStop(
    val name: String,
    val kind: RestKind,
    val lat: Double,
    val lon: Double,
    val route: String = "",
    val direction: String = ""
) {
    /** 화면에 보여줄 이름 (예: "안성휴게소 (부산방향)") */
    val displayName: String
        get() {
            val base = if (kind == RestKind.SHELTER && !name.contains("쉼터")) "$name 졸음쉼터" else name
            return if (direction.isNotBlank() && !base.contains(direction)) "$base ($direction)" else base
        }
}

/** 지금 위치 기준 쉼터 하나의 거리·시간 */
data class RestOption(
    val stop: RestStop,
    val distanceKm: Double,
    val etaMin: Int,
    val arrival: String
) {
    /** "4.2 km · 약 3분 · 22:31 도착" */
    val detail: String get() = "${"%.1f".format(distanceKm)} km · 약 ${etaMin}분 · $arrival 도착"
}

data class GeoFix(val lat: Double, val lon: Double, val bearing: Float?, val speedKmh: Float?)

// ───────────────── 위치 ─────────────────

interface LocationSource {
    suspend fun current(): GeoFix?
}

/** 시뮬레이션: 경부고속도로 부산방향 (오산 부근) 주행 중이라고 가정 */
class SimLocationSource(
    var fix: GeoFix = GeoFix(lat = 37.105, lon = 127.130, bearing = 170f, speedKmh = 90f)
) : LocationSource {
    override suspend fun current(): GeoFix = fix
}

/**
 * 실제 위치 (안드로이드 LocationManager).
 * 권한: ACCESS_FINE_LOCATION (adb shell pm grant 로 부여 — 권한 팝업이 멀티윈도우에서 안 보임)
 */
class AndroidLocationSource(private val context: Context) : LocationSource {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun granted() =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoFix? {
        if (!granted()) { Log.w("GYEOL_REST", "위치 권한 없음"); return null }
        val providers = lm.getProviders(true)
        // 1) 최근 위치
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) return last.toFix()
        // 2) 새 위치 한 번 받기 (최대 5초)
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        } ?: return last?.toFix()
        val fresh = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return (fresh ?: last)?.toFix()
    }

    private fun Location.toFix() = GeoFix(
        lat = latitude, lon = longitude,
        bearing = if (hasBearing() && hasSpeed() && speed > 3f) bearing else null,
        speedKmh = if (hasSpeed()) speed * 3.6f else null
    )
}

// ───────────────── 쉼터 데이터 ─────────────────

class RestRepository(private val context: Context) {

    @Volatile private var cache: List<RestStop>? = null
    var usingSample = false
        private set

    suspend fun all(): List<RestStop> = cache ?: withContext(Dispatchers.IO) {
        val list = mutableListOf<RestStop>()
        list += loadCsv("rest_areas.csv", RestKind.REST_AREA)
        list += loadCsv("drowsy_shelters.csv", RestKind.SHELTER)
        if (list.isEmpty()) {
            usingSample = true
            list += SAMPLE
            Log.w("GYEOL_REST", "assets에 CSV가 없어 예시 데이터 사용")
        } else {
            Log.i("GYEOL_REST", "쉼터 ${list.size}곳 로드")
        }
        list.also { cache = it }
    }

    /**
     * 진행 방향 앞쪽(±70°)에 있는 쉼터를 가까운 순으로.
     * 방향을 모르면(정차 중 등) 그냥 가까운 순.
     */
    suspend fun findAhead(fix: GeoFix, maxKm: Double = 60.0, count: Int = 5): List<RestOption> {
        val speed = (fix.speedKmh ?: 0f).let { if (it < 30f) 80f else it }
        val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val now = System.currentTimeMillis()
        return all().asSequence()
            .map { it to distanceKm(fix.lat, fix.lon, it.lat, it.lon) }
            .filter { (_, d) -> d in 0.3..maxKm }
            .filter { (s, _) ->
                val b = fix.bearing ?: return@filter true
                angleDiff(b.toDouble(), bearingDeg(fix.lat, fix.lon, s.lat, s.lon)) <= 70.0
            }
            .sortedBy { it.second }
            .take(count)
            .map { (s, d) ->
                val eta = (d / speed * 60).roundToInt().coerceAtLeast(1)
                RestOption(s, d, eta, fmt.format(Date(now + eta * 60_000L)))
            }
            .toList()
    }

    // ── CSV 읽기 ──

    private fun loadCsv(file: String, kind: RestKind): List<RestStop> {
        val bytes = runCatching { context.assets.open(file).use { it.readBytes() } }.getOrNull() ?: return emptyList()
        val text = decode(bytes)
        val rows = text.lineSequence().filter { it.isNotBlank() }.map(::splitCsv).toList()
        if (rows.size < 2) return emptyList()
        val header = rows.first().map { it.trim().removePrefix("﻿") }

        fun col(vararg keys: String) = header.indexOfFirst { h -> keys.any { h.contains(it, ignoreCase = true) } }
        val latI = col("위도", "lat", "Y좌표")
        val lonI = col("경도", "lon", "lng", "X좌표")
        var nameI = col("휴게소명", "쉼터명", "시설명", "명칭", "이름", "name")
        if (nameI < 0) nameI = header.indexOfFirst { it.endsWith("명") && !it.contains("노선") }
        val routeI = col("노선명", "도로명", "route")
        val dirI = col("방향", "direction")
        if (latI < 0 || lonI < 0 || nameI < 0) {
            Log.w("GYEOL_REST", "$file 열을 못 찾음: $header"); return emptyList()
        }

        return rows.drop(1).mapNotNull { r ->
            var lat = r.getOrNull(latI)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            var lon = r.getOrNull(lonI)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat > 100 && lon < 100) { val t = lat; lat = lon; lon = t }      // 위도/경도가 바뀌어 있으면
            if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return@mapNotNull null
            val name = r.getOrNull(nameI)?.trim()?.trim('"').orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            RestStop(
                name = name, kind = kind, lat = lat, lon = lon,
                route = r.getOrNull(routeI)?.trim().orEmpty(),
                direction = r.getOrNull(dirI)?.trim().orEmpty()
            )
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        for (cs in listOf("x-windows-949", "MS949", "EUC-KR")) {
            runCatching { return String(bytes, Charset.forName(cs)) }
        }
        return utf8
    }

    /** 따옴표를 처리하는 간단한 CSV 분리 */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    companion object {
        /**
         * CSV를 넣기 전 임시 예시 (경부고속도로 부산방향, 좌표는 대략값).
         * 실제 시연 전에는 공공데이터 CSV를 assets에 넣을 것.
         */
        val SAMPLE = listOf(
            RestStop("기흥휴게소", RestKind.REST_AREA, 37.240, 127.103, "경부선", "부산방향"),
            RestStop("안성휴게소", RestKind.REST_AREA, 37.020, 127.167, "경부선", "부산방향"),
            RestStop("망향휴게소", RestKind.REST_AREA, 36.935, 127.200, "경부선", "부산방향"),
            RestStop("입장거봉포도휴게소", RestKind.REST_AREA, 36.880, 127.215, "경부선", "부산방향"),
            RestStop("천안삼거리휴게소", RestKind.REST_AREA, 36.790, 127.190, "경부선", "부산방향")
        )

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }

        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        /** 음성 대답이 "응/네"인지 "아니"인지 (모르면 null) */
        fun yesNo(heard: String): Boolean? {
            val h = heard.replace(" ", "")
            return when {
                listOf("아니", "괜찮", "싫어", "됐어", "다음").any { h.contains(it) } -> false
                listOf("응", "네", "예", "그래", "좋아", "추가", "안내", "가자").any { h.contains(it) } -> true
                else -> null
            }
        }
    }
}
