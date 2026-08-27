package com.xinsu.signalnumbers.config

import android.net.Uri

object ConfigContract {
    const val AUTHORITY = "com.xinsu.signalnumbers.config"
    val URI: Uri = Uri.parse("content://$AUTHORITY/config")
    const val METHOD_GET = "get_config"
    const val METHOD_LOG = "append_log"
    const val METHOD_FAILURE = "record_failure"
    const val METHOD_CLEAR_SAFE_MODE = "clear_safe_mode"
    const val EXTRA_MESSAGE = "message"
}
