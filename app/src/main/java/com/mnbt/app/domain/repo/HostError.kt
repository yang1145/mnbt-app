package com.mnbt.app.domain.repo

/** 扫码地址对应的站点已经绑定过了。UI 应引导用户直接用已有条目，而不是重复绑定。 */
class HostAlreadyBoundException(val baseUrl: String) :
    Exception("该站点已绑定：$baseUrl")
