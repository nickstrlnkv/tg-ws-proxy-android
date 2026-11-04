package ru.flowseal.tgwsproxy.core

import android.util.Log

object AppLog {
    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Exception) {
            println("[$tag] $message")
        }
    }

    fun d(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Exception) {
            println("[$tag] $message")
        }
    }

    fun w(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Exception) {
            System.err.println("[$tag] WARN: $message")
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        try {
            Log.e(tag, message, t)
        } catch (_: Exception) {
            System.err.println("[$tag] ERROR: $message")
            t?.printStackTrace()
        }
    }
}
