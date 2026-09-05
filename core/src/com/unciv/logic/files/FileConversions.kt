package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.json.json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.encoding.decodingWith
import kotlin.io.encoding.encodingWith

@OptIn(ExperimentalEncodingApi::class)
object FileConversions {

    fun zip(data: String): String {
        val bos = ByteArrayOutputStream()
        zippedOutputStream(bos).use { it.write(data.toByteArray(Charsets.UTF_8)) }
        return bos.toString(Charsets.UTF_8.name())
    }

    fun unzip(data: String): String {
        return unzippedInputStream(ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Wraps [output]: bytes written to the result are gzip-compressed, then base64-encoded into [output]. */
    private fun zippedOutputStream(output: OutputStream): OutputStream =
        GZIPOutputStream(output.encodingWith(Base64.Default))

    /** Wraps [input], which must contain base64-encoded gzip data: bytes read from the result are the decompressed original. */
    private fun unzippedInputStream(input: InputStream): InputStream =
        GZIPInputStream(input.decodingWith(Base64.Default))

    /** Plain base64 encoding, no gzip - e.g. for encoding a checksum digest as text. */
    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

    /** Reads [file] as JSON of [type], transparently gunzip+base64-decoding it if that's how it was saved -
     *  otherwise (or if that fails), falls back to reading it as plain JSON text. */
    fun <T> readJson(file: FileHandle, type: Class<T>): T? {
        val reader = try {
            unzippedInputStream(file.read()).reader(Charsets.UTF_8)
        } catch (ex: Exception) {
            file.reader(Charsets.UTF_8.name())
        }
        return reader.use { json().fromJson(type, it) }
    }

    /** Writes [obj] as JSON to [file], gzip-compressing and base64-encoding it first if [zip] is true. */
    fun writeJson(file: FileHandle, obj: Any, zip: Boolean) {
        if (zip) {
            zippedOutputStream(file.write(false)).writer(Charsets.UTF_8).use {
                json().toJson(obj, it)
            }
        } else {
            json().toJson(obj, file)
        }
    }
}
