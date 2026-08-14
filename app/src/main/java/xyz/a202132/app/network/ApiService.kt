package xyz.a202132.app.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Url
import xyz.a202132.app.data.model.AccountBindingRequest
import xyz.a202132.app.data.model.AccountActionResponse
import xyz.a202132.app.data.model.AccountDeleteRequest
import xyz.a202132.app.data.model.AccountLoginRequest
import xyz.a202132.app.data.model.AccountProfileUpdateRequest
import xyz.a202132.app.data.model.AccountRegisterRequest
import xyz.a202132.app.data.model.AccountRegisterResponse
import xyz.a202132.app.data.model.SpeedShareRequest
import xyz.a202132.app.data.model.SpeedShareResponse
import xyz.a202132.app.data.model.SpeedShareResultsResponse
import xyz.a202132.app.data.model.TelegramTaskResponse
import xyz.a202132.app.data.model.TelegramTaskSubmitRequest
import xyz.a202132.app.data.model.TrafficReportRequest
import xyz.a202132.app.data.model.TrafficReportResponse

interface ApiService {
    
    @GET
    suspend fun getBackendConfig(@Url url: String): String

    @GET
    suspend fun getSubscription(@Url url: String): Response<ResponseBody>

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

    @GET
    suspend fun getTelegramTask(
        @Url url: String
    ): TelegramTaskResponse

    @POST
    suspend fun shareSpeedResults(
        @Url url: String,
        @Body request: SpeedShareRequest
    ): SpeedShareResponse

    @GET
    suspend fun getSpeedShareResults(
        @Url url: String
    ): SpeedShareResultsResponse

    @POST
    suspend fun submitTelegramTask(
        @Url url: String,
        @Body request: TelegramTaskSubmitRequest
    ): TelegramTaskResponse

    @POST
    suspend fun reportTraffic(
        @Url url: String,
        @Body request: TrafficReportRequest
    ): TrafficReportResponse
}
