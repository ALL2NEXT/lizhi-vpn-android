package xyz.a202132.app.network

import retrofit2.http.GET
import retrofit2.http.Url

interface ApiService {
    
    @GET
    suspend fun getBackendConfig(@Url url: String): String
}
