package io.github.HanJason233.chimera.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Url
import io.github.HanJason233.chimera.data.model.AccountBindingRequest
import io.github.HanJason233.chimera.data.model.AccountActionResponse
import io.github.HanJason233.chimera.data.model.AccountDeleteRequest
import io.github.HanJason233.chimera.data.model.AccountLoginRequest
import io.github.HanJason233.chimera.data.model.AccountProfileUpdateRequest
import io.github.HanJason233.chimera.data.model.AccountRegisterRequest
import io.github.HanJason233.chimera.data.model.AccountRegisterResponse
import io.github.HanJason233.chimera.data.model.TrafficReportRequest
import io.github.HanJason233.chimera.data.model.TrafficReportResponse

interface ApiService {
    
    @GET
    suspend fun getBackendConfig(@Url url: String): String

    @GET
    suspend fun getSubscription(
        @Url url: String,
        @Header("User-Agent") userAgent: String?
    ): Response<ResponseBody>

    @POST
    suspend fun registerAccount(
        @Url url: String,
        @Body request: AccountRegisterRequest
    ): AccountRegisterResponse

    @POST
    suspend fun bindAccount(
        @Url url: String,
        @Body request: AccountBindingRequest
    ): AccountRegisterResponse

    @POST
    suspend fun loginAccount(
        @Url url: String,
        @Body request: AccountLoginRequest
    ): AccountRegisterResponse

    @POST
    suspend fun deleteAccount(
        @Url url: String,
        @Body request: AccountDeleteRequest
    ): AccountActionResponse

    @PATCH
    suspend fun updateAccountProfile(
        @Url url: String,
        @Body request: AccountProfileUpdateRequest
    ): AccountRegisterResponse

    @POST
    suspend fun reportTraffic(
        @Url url: String,
        @Body request: TrafficReportRequest
    ): TrafficReportResponse
}
