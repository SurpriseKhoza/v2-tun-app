package io.surprise.vpn.core

import libXray.LibXray
import org.json.JSONObject

object XrayEngine {
    const val CORE_VERSION = "26.11.9"
    private const val API_VERSION = 3

    fun run(xrayJson: String): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "runXray")
            .put("payload", JSONObject().put("xrayJson", xrayJson))
        return invoke(request.toString())
    }

    fun stop(): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "stopXray")
            .put("payload", JSONObject())
        return invoke(request.toString())
    }

    fun isRunning(): Boolean {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", "getXrayState")
            .put("payload", JSONObject())
        val response = invoke(request.toString())
        return response.optBoolean("success", false) &&
            response.optJSONObject("data")?.optBoolean("running", false) == true
    }

    private fun invoke(request: String): JSONObject {
        val raw = LibXray.invoke(request)
        return runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalStateException("The Xray engine returned an unreadable response.") }
    }

    fun errorMessage(response: JSONObject): String =
        response.optString("error").ifBlank { "The Xray engine could not start the tunnel." }
}
