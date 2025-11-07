package eu.kanade.tachiyomi.extension.all.tachidesk

import android.util.Log
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.network.okHttpClient
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.LoginMutation
import eu.kanade.tachiyomi.extension.all.tachidesk.apollo.RefreshTokenMutation
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.flow.single
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class TokenManager(private val mode: Tachidesk.AuthMode, private val user: String, private val pass: String, private val baseUrl: String, private val baseClient: OkHttpClient) {
    private var currentToken: String? = null
    private var refreshToken: String? = null
    private var cookies: String = ""

    private data class TokenTuple(val accessToken: String?, val refreshToken: String?)

    public fun getBasicHeaders(): List<HttpHeader> {
        val headers = mutableListOf<HttpHeader>()
        if (mode == Tachidesk.AuthMode.BASIC_AUTH && pass.isNotEmpty() && user.isNotEmpty()) {
            val credentials = Credentials.basic(user, pass)
            headers.add(HttpHeader("Authorization", credentials))
        }
        return headers.toList()
    }

    public fun getHeaders(): List<HttpHeader> {
        val headers = mutableListOf<HttpHeader>()
        when (mode) {
            Tachidesk.AuthMode.NONE -> { }
            Tachidesk.AuthMode.BASIC_AUTH -> {
                val credentials = Credentials.basic(user, pass)
                headers.add(HttpHeader("Authorization", credentials))
            }
            Tachidesk.AuthMode.SIMPLE_LOGIN -> headers.add(HttpHeader("Cookie", cookies))
            Tachidesk.AuthMode.UI_LOGIN -> headers.add(HttpHeader("Authorization", "Bearer $currentToken"))
        }
        return headers.toList()
    }

    public fun token(): Any? {
        return when (mode) {
            Tachidesk.AuthMode.SIMPLE_LOGIN -> cookies
            Tachidesk.AuthMode.UI_LOGIN -> TokenTuple(currentToken, refreshToken)
            else -> null
        }
    }

    public fun <D : Operation.Data> ApolloRequest.Builder<D>.addToken(): ApolloRequest.Builder<D> {
        return when (mode) {
            Tachidesk.AuthMode.SIMPLE_LOGIN -> this.addHttpHeader("Cookie", cookies)
            Tachidesk.AuthMode.UI_LOGIN -> this.addHttpHeader("Authorization", "Bearer $currentToken")
            else -> this
        }
    }

    public fun Request.Builder.addToken(): Request.Builder {
        return when (mode) {
            Tachidesk.AuthMode.BASIC_AUTH -> {
                val credentials = Credentials.basic(user, pass)
                this.header("Authorization", credentials)
            }
            Tachidesk.AuthMode.SIMPLE_LOGIN -> this.header("Cookie", cookies)
            Tachidesk.AuthMode.UI_LOGIN -> this.header("Authorization", "Bearer $currentToken")
            else -> this
        }
    }

    public suspend fun refresh(oldToken: Any?) {
        Log.v(TAG, "Refreshing token for mode $mode")
        when (mode) {
            Tachidesk.AuthMode.SIMPLE_LOGIN -> {
                if (oldToken != cookies) {
                    Log.i(TAG, "Refusing to refresh cookie: Changed since original call, another request likely already refreshed, try again")
                    return
                }

                val formBody = FormBody.Builder().add("user", user).add("pass", pass).build()
                val request = Request.Builder().url(baseUrl + "/login.html").post(formBody).build()
                val result = baseClient.newBuilder().followRedirects(false).build().newCall(request).await()
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
            Tachidesk.AuthMode.UI_LOGIN -> {
                if (oldToken != TokenTuple(currentToken, refreshToken)) {
                    Log.i(TAG, "Refusing to refresh token: Changed since original call, another request likely already refreshed, try again")
                    return
                }

                val apollo = createApolloClient()
                refreshToken?.let {
                    Log.v(TAG, "Refresh token known, asking for new access token")
                    val response = apollo.mutation(RefreshTokenMutation(it))
                        .toFlow()
                        .single()
                    if (response.hasErrors()) {
                        Log.w(TAG, "Invalid refresh token")
                        this.refreshToken = null
                    }

                    this.currentToken = response.dataAssertNoErrors.refreshToken.accessToken
                    return
                }

                Log.v(TAG, "No previous login, asking for tokens with username and password")
                val response = apollo.mutation(LoginMutation(user, pass))
                    .toFlow()
                    .single()
                if (response.hasErrors()) {
                    Log.w(TAG, "Invalid credentials")
                }
                currentToken = response.dataAssertNoErrors.login.accessToken
                refreshToken = response.dataAssertNoErrors.login.refreshToken
            }
            else -> {}
        }
    }

    private fun createApolloClient(): ApolloClient {
        return ApolloClient.Builder()
            .serverUrl("$baseUrl/api/graphql")
            .okHttpClient(baseClient)
            .build()
    }

    companion object {
        private const val TAG = "Tachidesk.TokenManager"
    }
}
