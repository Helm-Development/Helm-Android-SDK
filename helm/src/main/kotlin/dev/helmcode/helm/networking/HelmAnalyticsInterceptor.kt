package dev.helmcode.helm.networking

import dev.helmcode.helm.Helm
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that stamps Helm identity headers
 * (X-Helm-Installation-Id, X-Helm-User-Hash, X-Helm-Session-Id, ...) onto every
 * request, so the backend's ApiHitMiddleware can attribute hits.
 *
 * Add to the app's client: `builder.addInterceptor(HelmAnalyticsInterceptor())`.
 * Requires OkHttp on the app's classpath (this SDK declares it compileOnly).
 */
class HelmAnalyticsInterceptor(
    private val headersProvider: () -> Map<String, String> = { Helm.analytics.headers() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        headersProvider().forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
