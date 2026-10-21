package io.github.fanti1.audiolivre.data

import java.security.MessageDigest

// A short, stable key per book: content:// URI strings are long and full of characters that don't
// belong in preference keys or file names.
fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
