package io.johnsonlee.mitmproxy.internal.proxy

import io.johnsonlee.mitmproxy.Middleware
import io.johnsonlee.mitmproxy.getValue
import io.johnsonlee.mitmproxy.internal.middleware.BootstrapMiddlewares
import io.johnsonlee.mitmproxy.internal.middleware.EndOfChainException
import io.johnsonlee.mitmproxy.internal.middleware.FlowRecordingMiddleware
import io.johnsonlee.mitmproxy.internal.middleware.MapToLocalMiddleware
import io.johnsonlee.mitmproxy.internal.middleware.MapToRemoteMiddleware
import io.johnsonlee.mitmproxy.internal.middleware.MicrometerMiddleware
import io.johnsonlee.mitmproxy.internal.middleware.MiddlewarePipeline
import io.johnsonlee.mitmproxy.internal.middleware.ServerToProxyMiddleware
import io.johnsonlee.mitmproxy.internal.util.release
import io.johnsonlee.mitmproxy.internal.util.retain
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import org.littleshoot.proxy.HttpFiltersAdapter
import org.littleshoot.proxy.impl.ClientToProxyConnection
import org.springframework.context.ApplicationContext

internal class MitmFilters(
        private val application: ApplicationContext,
        originalRequest: HttpRequest,
        ctx: ChannelHandlerContext?
) : HttpFiltersAdapter(originalRequest, ctx), ApplicationContext by application {

    private val bootstrap: BootstrapMiddlewares by application

    val originalScheme: String
        get() = if ((ctx?.handler() as? ClientToProxyConnection)?.sslEngine == null) "http" else "https"

    val context: ChannelHandlerContext? by lazy { ctx }

    private lateinit var request: HttpRequest

    private lateinit var outbound: MiddlewarePipeline

    override fun proxyToServerRequest(httpObject: HttpObject?): HttpResponse? {
        this.request = (httpObject as? HttpRequest)?.takeIf {
            it.method() != HttpMethod.CONNECT
        }?.retain() ?: return null

        val middlewares = mutableListOf<Middleware>()
        middlewares += bootstrap.get()
        middlewares += FlowRecordingMiddleware(this)
        middlewares += MicrometerMiddleware(this)
        middlewares += MapToLocalMiddleware(this)
        middlewares += MapToRemoteMiddleware(this)
        this.outbound = MiddlewarePipeline(this.request, middlewares)

        return try {
            outbound()
        } catch (e: EndOfChainException) {
            null
        }
    }

    override fun serverToProxyResponse(httpObject: HttpObject?): HttpObject? {
        val response = (httpObject as? HttpResponse)?.retain() ?: return null
        val inbound = MiddlewarePipeline(this.request, this.outbound.middlewares + ServerToProxyMiddleware(response))

        try {
            return inbound()
        } finally {
            request.release()
            response.release()
        }
    }

}