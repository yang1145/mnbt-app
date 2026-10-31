package com.mnbt.app.data.remote.api

/**
 * bridge 插件的协议错误码。
 *
 * 注意 0 的语义冲突：MNBT 自己的 Response::build 把数字 0 判为失败，
 * 而本协议把 0 定义为成功。规避方式是 bridge 必须显式带上 success 布尔，
 * 解析器也优先看 success（见 [EnvelopeParser]）。写插件时不要图省事
 * 直接调 Response::exit_json(0, ...)。
 */
object MnbtCodes {
    const val OK = 0

    /** 1001 令牌失效 / 1005 主机已被删除：契约 §12.20 都走「重新配对」，不做整页失效 */
    const val TOKEN_INVALID = 1001
    const val PAIR_CODE_INVALID = 1002

    /** PRD §4.6：1003 = 主机已到期（配对码过期归 1002，勿与此码混用） */
    const val HOST_EXPIRED = 1003
    const val ACCOUNT_BANNED = 1004
    const val HOST_REMOVED = 1005
    const val NODE_ERROR = 1006
    const val BAD_PARAM = 1007

    /** 1008 限流：包络带 `retry_after`（秒），UI 按秒数倒计时提示 */
    const val THROTTLED = 1008
    const val UPSTREAM_FAILURE = 1009

    /**
     * 1010 有两种含义（契约 §3/§11）：面板侧根本没有这个能力（例如 db.create），
     * 或插件版本过低。
     * 都是**非破坏性**提示 → Snackbar，绝不可当成整页失效或引导重扫。
     */
    const val PLUGIN_TOO_OLD = 1010

    /** 5000 服务端异常：`data.trace_id` 是客户报给客服的唯一线索，见 [EnvelopeParser] */
    const val SERVER_ERROR = 5000
}
