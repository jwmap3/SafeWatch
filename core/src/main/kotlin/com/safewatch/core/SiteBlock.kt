package com.safewatch.core

/**
 * Which websites the browser will not open: adult sites (from a list of tens of thousands of them) and,
 * when chosen, social media. A site is blocked along with every address under it (m.example.com, and so on).
 */
class SiteBlock(private val adult: Set<String>, private val blockAdult: Boolean = true, private val blockSocial: Boolean = true) {

    /** Why [host] is blocked ("adult" or "social"), or null when it is not. */
    fun reason(host: String?): String? {
        var name = host?.lowercase()?.trimEnd('.') ?: return null
        if (name.isEmpty()) return null
        while (true) {
            if (blockSocial && name in SOCIAL) return "social"
            if (blockAdult && (name in adult || name in ADULT_EXTRA)) return "adult"
            val dot = name.indexOf('.')
            if (dot < 0 || name.indexOf('.', dot + 1) < 0) return null
            name = name.substring(dot + 1)
        }
    }

    companion object {
        /** Twitter (X), Reddit and Instagram, with the addresses their pages and links use. */
        val SOCIAL = setOf(
            "twitter.com", "x.com", "t.co", "twimg.com", "twitpic.com",
            "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "reddit.app.link",
            "instagram.com", "cdninstagram.com", "instagr.am", "ig.me",
        )

        /** A few of the largest adult sites, in case a list leaves one out. */
        val ADULT_EXTRA = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "onlyfans.com", "chaturbate.com", "stripchat.com",
            "youporn.com", "redtube.com", "spankbang.com", "eporner.com", "tube8.com", "brazzers.com", "fansly.com",
            "rule34.xxx", "e-hentai.org", "nhentai.net", "hanime.tv", "bongacams.com", "livejasmin.com", "cam4.com",
            "motherless.com", "porn.com", "beeg.com", "txxx.com", "hqporner.com", "daftsex.com", "porntrex.com",
        )
    }
}
