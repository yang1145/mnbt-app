package com.mnbt.app.data.remote.api

/**
 * 每一个子类对应一种 UI 需要做出不同反应的状态，不是随意细分：
 * TokenRejected → 引导重新扫码；HostExpired → 引导联系站长续费；
 * Certificate → 引导重新确认证书；Throttled → 倒计时后重试；
 * Unreachable → 显示离线并允许重试。其余一律直接展示 message。
 */
sealed class MnbtException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    class TokenRejected(
        message: String = "登录凭据已失效，请在网页面板重新生成配对码后再扫一次",
    ) : MnbtException(message)

    class HostExpired(message: String) : MnbtException(message)

    class AccountBanned(message: String) : MnbtException(message)

    class PanelClosed(message: String) : MnbtException(message)

    class NodeError(message: String) : MnbtException(message)

    class Throttled(val retryAfterSeconds: Int, message: String) : MnbtException(message)

    class PluginTooOld(message: String) : MnbtException(message)

    class Certificate(message: String, cause: Throwable?) : MnbtException(message, cause)

    class Unreachable(message: String, cause: Throwable?) : MnbtException(message, cause)

    class HttpStatus(val status: Int, message: String) : MnbtException(message)

    /** 返回体不符合任何已知的 MNBT 响应格式，[raw] 已截断，可展示给用户排查 */
    class Protocol(message: String, val raw: String) : MnbtException(message)

    class Server(val code: Int, message: String) : MnbtException(message)
}
