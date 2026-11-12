package io.github.HanJason233.chimera.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.security.handling.SecurityProxySelector
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

object NetworkClient {

    fun withUserAgent(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val configuredBuilder = if (BuildConfig.SECURITY_BLOCK_HTTP_PROXY) {
            builder.proxySelector(SecurityProxySelector)
        } else {
            builder
        }
        return configuredBuilder.addInterceptor { chain ->
            val originalRequest = chain.request()
            val request = if (originalRequest.header("User-Agent").isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("User-Agent", AppConfig.HTTP_USER_AGENT)
                    .build()
            } else {
                originalRequest
            }
            chain.proceed(request)
        }
    }
    
    private val okHttpClient = withUserAgent(OkHttpClient.Builder())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = GsonBuilder()
        .setLenient()
        .create()

    // 订阅响应的字符串转换器
    private val stringConverterFactory = object : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *>? {
            return if (type == String::class.java) {
                Converter<ResponseBody, String> { it.string() }
            } else {
                null
            }
        }
    }
    
    @Volatile
    private var apiServiceInstance: ApiService = createApiService(AppConfig.BACKEND_URL)

    val apiService: ApiService
        get() = apiServiceInstance

    /** 后端地址切换后重建 Retrofit，后续请求将使用新地址。 */
    fun reloadBackend() {
        apiServiceInstance = createApiService(AppConfig.BACKEND_URL)
    }

    private fun createApiService(baseUrl: String): ApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("${baseUrl.trimEnd('/')}/")
            .client(okHttpClient)
            .addConverterFactory(stringConverterFactory)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        return retrofit.create(ApiService::class.java)
    }
}
