package com.rerunguide.core

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** `HTTP.data` never follows https → http: the token header would go out in the clear. */
class HTTPRedirectTests {
    @Test fun testHttpsToHttpRedirectIsRefused() {
        // A throwaway certificate for 127.0.0.1, made by the test JDK's own keytool.
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val ks = File(dir, "k.p12"); val pass = "test-only".toCharArray()
        val made = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-keystore", ks.path,
            "-storetype", "PKCS12", "-storepass", String(pass), "-alias", "k", "-keyalg", "RSA", "-dname", "CN=127.0.0.1",
            "-ext", "SAN=ip:127.0.0.1", "-validity", "1").redirectErrorStream(true).start().waitFor()
        assertTrue("keytool", made == 0)
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, pass) } }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance("SunX509").apply { init(store, pass) }.keyManagers,
                 TrustManagerFactory.getInstance("PKIX").apply { init(store) }.trustManagers, null)
        }
        val tokens = Collections.synchronizedList(mutableListOf<String?>())
        val plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { tokens.add(it.requestHeaders.getFirst("X-Plex-Token")); it.sendResponseHeaders(200, -1); it.close() }
            start()
        }
        val secure = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            createContext("/") { it.responseHeaders.add("Location", "http://127.0.0.1:${plain.address.port}/"); it.sendResponseHeaders(302, -1); it.close() }
            start()
        }
        val trusted = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        try {
            val req = URLRequest("https://127.0.0.1:${secure.address.port}/").apply { setValue("server-token", forHTTPHeaderField = "X-Plex-Token") }
            try { runBlocking { HTTP.data(req) }; fail("followed the redirect") } catch (_: ProtocolException) {}
            assertTrue(tokens.toString(), tokens.isEmpty())
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(trusted)
            plain.stop(0); secure.stop(0); dir.deleteRecursively()
        }
    }
}
