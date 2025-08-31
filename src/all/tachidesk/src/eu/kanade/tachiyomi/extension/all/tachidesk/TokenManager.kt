package eu.kanade.tachiyomi.extension.all.tachidesk

import android.util.Log
import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import eu.kanade.tachiyomi.network.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class TokenManager(private val mode: Tachidesk.AuthMode, private val user: String, private val pass: String, private val baseUrl: String, private val baseClient: OkHttpClient) {
    private var currentToken: String? = null
    private var refreshToken: String? = null
    private var cookies: String = ""

    public fun <D : Operation.Data> ApolloRequest.Builder<D>.addToken(): ApolloRequest.Builder<D> {
        return when (mode) {
            Tachidesk.AuthMode.SIMPLE_LOGIN -> this.addHttpHeader("Cookie", cookies)
            else -> this
        }
    }

    public suspend fun refresh() {
        Log.v(TAG, "Refreshing token for mode $mode")
        when (mode) {
            Tachidesk.AuthMode.SIMPLE_LOGIN -> {
                val formBody = FormBody.Builder().add("user", user).add("pass", pass).build()
                val request = Request.Builder().url(baseUrl + "/login.html").post(formBody).build()
                val result = baseClient.newCall(request).await()
                // login.html redirects when successful
                if (!result.isRedirect) {
                    var err = result.body.string().replace(".*<div class=\"error\">([^<]*)</div>.*".toRegex(RegexOption.DOT_MATCHES_ALL)) {
                        it.groups[1]!!.value
                    }
                    Log.v(TAG, "Cookie refresh failed, server did not redirect, error: $err")
                    throw Exception("Login failed: $err")
                }
                cookies = result.header("Set-Cookie", "")!!
                Log.v(TAG, "Cookie successfully refreshed")
            }
            else -> {}
        }
    }

    companion object {
        private const val TAG = "Tachidesk.TokenManager"
    }
}
