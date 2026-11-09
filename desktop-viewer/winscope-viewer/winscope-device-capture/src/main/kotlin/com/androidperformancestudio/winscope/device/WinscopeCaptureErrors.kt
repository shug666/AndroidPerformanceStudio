package com.androidperformancestudio.winscope.device

/** Invalid client request mapped to HTTP 400 by the proxy server. */
public class WinscopeBadRequestException(
    public override val message: String,
) : Exception(message)

/** Device-side capture failure mapped to HTTP 500 by the proxy server. */
public class WinscopeDeviceCaptureException(
    public override val message: String,
) : Exception(message)
