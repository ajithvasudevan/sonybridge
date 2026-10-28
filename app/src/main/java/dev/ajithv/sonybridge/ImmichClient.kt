package dev.ajithv.sonybridge

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Minimal immich REST client: upload asset + ensure/append date album. */
class ImmichClient(baseUrl: String, private val apiKey: String) {

    private val base = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.MINUTES)   // large video clips over cellular
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    data class UploadResult(val id: String, val status: String)

    /** POST /api/assets (multipart, streamed). Returns id + status ("created"/"duplicate"), or null on failure. */
    fun upload(file: File, name: String, iso: String): UploadResult? {
        val type = mediaType(name)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceAssetId", "sb-$name-${file.length()}")
            .addFormDataPart("deviceId", "sonybridge")
            .addFormDataPart("fileCreatedAt", iso)
            .addFormDataPart("fileModifiedAt", iso)
            .addFormDataPart("assetData", name, file.asRequestBody(type.toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url("$base/api/assets")
            .addHeader("x-api-key", apiKey).post(body).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) return null
            val o = JSONObject(txt)
            val id = o.optString("id", "")
            if (id.isBlank()) return null
            return UploadResult(id, o.optString("status", "created"))
        }
    }

    /** Find an album by exact name, else create it. Returns albumId or null. */
    fun ensureAlbum(name: String): String? {
        // list
        val lreq = Request.Builder().url("$base/api/albums")
            .addHeader("x-api-key", apiKey).get().build()
        http.newCall(lreq).execute().use { r ->
            if (r.isSuccessful) {
                val arr = JSONArray(r.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.optString("albumName") == name) return a.optString("id")
                }
            }
        }
        // create
        val creq = Request.Builder().url("$base/api/albums")
            .addHeader("x-api-key", apiKey)
            .post(JSONObject().put("albumName", name).toString()
                .toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        http.newCall(creq).execute().use { r ->
            if (!r.isSuccessful) return null
            return JSONObject(r.body?.string().orEmpty()).optString("id").ifBlank { null }
        }
    }

    /** PUT /api/albums/{id}/assets. Idempotent (already-present assets are ignored by immich). */
    fun addToAlbum(albumId: String, assetId: String): Boolean {
        val req = Request.Builder().url("$base/api/albums/$albumId/assets")
            .addHeader("x-api-key", apiKey)
            .put(JSONObject().put("ids", JSONArray().put(assetId)).toString()
                .toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().use { r -> return r.isSuccessful }
    }

    fun ping(): Boolean {
        val req = Request.Builder().url("$base/api/server/version")
            .addHeader("x-api-key", apiKey).get().build()
        return try { http.newCall(req).execute().use { it.isSuccessful } } catch (_: Exception) { false }
    }

    private fun mediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "heif" -> "image/heic"
        "png" -> "image/png"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "arw" -> "image/x-sony-arw"
        "dng" -> "image/x-adobe-dng"
        else -> "application/octet-stream"
    }
}
