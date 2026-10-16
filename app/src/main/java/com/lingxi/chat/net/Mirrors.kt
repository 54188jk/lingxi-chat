package com.lingxi.chat.net

/**
 * 一个文件在外部能从哪里取到，全部集中在这里列，更新包 / 内置识别文件 / 商店下载三处共用。
 * 顺序按「国内先能连上、且实测会给分段」排：Gitee 直连最前，其后是几个 GitHub 加速站，
 * 最后才是 GitHub 官方与原始来源。列得多不是为了全部用上，而是任何一条挂了都不影响开始。
 */
object Mirrors {

    /** GitHub 资产的加速前缀；拼在原直链前面用 */
    private val PROXIES = listOf(
        "https://gh-proxy.com/" to "加速线路 1",
        "https://ghfast.top/" to "加速线路 2",
        "https://ghproxy.net/" to "加速线路 3",
        "https://gh.ddlc.top/" to "加速线路 4",
        "https://github.moeyy.xyz/" to "加速线路 5",
        "https://mirror.ghproxy.com/" to "加速线路 6",
        "https://gh-proxy.eu.org/" to "加速线路 7"
    )

    /** CNB 镜像仓只放安装包附件（2026-10-16 用户点头开的），从 v1.190 起每版一份 */
    const val CNB_FROM = "1.190"

    fun cnbUrl(tag: String, file: String) =
        "https://cnb.cool/tuui.rrj/lingxi-chat-release/-/releases/download/$tag/$file"

    fun giteeUrl(repoRepo: String, tag: String, file: String) =
        "https://gitee.com/$repoRepo/releases/download/$tag/$file"

    fun githubUrl(repoRepo: String, tag: String, file: String) =
        "https://github.com/$repoRepo/releases/download/$tag/$file"

    /**
     * 展开成候选线路：Gitee（整包，实测不给分段）+ 各加速站（给分段）+ GitHub 官方 + 额外的原始来源。
     * 同一条地址重复出现时只留一次，交给 FastFetcher 之前先去重。
     */
    fun forAsset(
        gitee: String = "",
        github: String = "",
        cnb: String = "",
        extra: List<Pair<String, String>> = emptyList()
    ): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        if (gitee.isNotBlank()) list.add(gitee to "国内线路")
        if (cnb.isNotBlank()) list.add(cnb to "国内备用线路")
        if (github.isNotBlank()) {
            PROXIES.forEach { (prefix, label) -> list.add(prefix + github to label) }
            list.add(github to "官方线路")
        }
        list.addAll(extra)
        val seen = HashSet<String>()
        return list.filter { seen.add(it.first) }
    }
}
