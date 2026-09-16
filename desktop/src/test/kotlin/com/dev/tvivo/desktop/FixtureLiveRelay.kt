package com.dev.tvivo.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Fixture-only loopback relay for exercising a live-style, non-seekable input.
 * It deliberately omits Content-Length, rejects Range, and repeats the TS bytes
 * slowly until the client closes the connection.
 */
fun main(args: Array<String>) {
    val options = args.toList().windowed(2, 2, partialWindows = true)
        .associate { window -> window[0] to window.getOrNull(1) }
    val fixture = File(options["--fixture"] ?: error("Missing --fixture <path>"))
    require(fixture.isFile) { "Fixture does not exist: ${fixture.absolutePath}" }
    require(fixture.extension.equals("ts", ignoreCase = true)) { "The relay accepts a TS fixture only." }
    val port = (options["--port"] ?: "8765").toInt()
    val chunkBytes = (options["--chunk-bytes"] ?: "18800").toInt()
    val delayMs = (options["--delay-ms"] ?: "250").toLong()
    require(port in 1..65535)
    require(chunkBytes > 0)
    require(delayMs >= 0)

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val stopped = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    server.createContext("/fixture") { exchange -> serve(exchange, fixture, chunkBytes, delayMs) }
    server.executor = executor
    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "fixture-live-relay-shutdown") {
        server.stop(0)
        executor.shutdownNow()
        stopped.countDown()
    })
    server.start()
    println("Fixture live relay listening at http://127.0.0.1:$port/fixture")
    println("fixture=${fixture.absolutePath} chunkBytes=$chunkBytes delayMs=$delayMs")
    stopped.await()
}

private fun serve(exchange: HttpExchange, fixture: File, chunkBytes: Int, delayMs: Long) {
    try {
        val request = exchange
        if (request.requestMethod != "GET") {
            request.sendResponseHeaders(405, -1)
            return
        }
        if (request.requestHeaders.getFirst("Range") != null) {
            request.responseHeaders.add("Accept-Ranges", "none")
            request.sendResponseHeaders(416, -1)
            return
        }
        request.responseHeaders.add("Content-Type", "video/mp2t")
        request.responseHeaders.add("Cache-Control", "no-store")
        request.responseHeaders.add("Connection", "close")
        // A zero length selects chunked transfer encoding: no Content-Length.
        request.sendResponseHeaders(200, 0)
        request.responseBody.use { output ->
            val buffer = ByteArray(chunkBytes)
            while (true) {
                fixture.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        output.flush()
                        if (delayMs > 0) Thread.sleep(delayMs)
                    }
                }
            }
        }
    } finally {
        exchange.close()
    }
}
