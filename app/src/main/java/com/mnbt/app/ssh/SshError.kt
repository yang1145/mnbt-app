package com.mnbt.app.ssh

/**
 * 连接阶段能返回给 UI 的失败原因。每一种对应一句能直接显示的人话，
 * 所以文案写在这里而不是散在各个 Composable 里。
 */
sealed class SshError(val message: String) {

    /** TCP 都没建起来：地址写错、端口没开、不在同一网段。 */
    class Unreachable(host: String, port: Int, cause: String) :
        SshError("连不上 $host:$port（$cause）")

    class Timeout(host: String, port: Int) :
        SshError("连接 $host:$port 超时，请检查地址、端口与网络")

    /**
     * @param credentialLost 私钥/口令已不在密文库里（例如 Keystore 主密钥失效后被重建），
     *   此时重试没有意义，得让用户重新录入。
     */
    class CredentialMissing(val what: String) :
        SshError("$what 已丢失，请重新编辑该连接并录入")

    class AuthFailed(val detail: String) :
        SshError("认证失败：$detail")

    class BadPrivateKey(detail: String) :
        SshError("私钥无法解析：$detail")

    /** TOFU 记录过的公钥与本次服务器出示的不一致，可能是中间人，也可能是服务器重装过。 */
    class HostKeyChanged(val expected: String, val actual: String) :
        SshError("服务器公钥与首次连接时记录的不一致\n记录：$expected\n当前：$actual")

    class NoShell(detail: String) :
        SshError("服务器拒绝了 shell 请求：$detail")

    class Unknown(detail: String) :
        SshError("SSH 错误：$detail")
}
