package com.downloader.cloud;

/**
 * 支持解析的网盘平台。
 * <p>每个平台对应一条独立的凭据配置项（Cookie 文本，可留空），
 * 需要登录的平台在 UI 中会给出填写提示。
 */
public enum CloudDrive {

    QUARK("夸克网盘", "cloud.cookie.quark", "quark.cn", true,
            "需填写夸克网页版 Cookie（浏览器登录 pan.quark.cn 后复制，至少包含 __puus）"),
    UC("UC网盘", "cloud.cookie.uc", "uc.cn", true,
            "需填写 UC 网页版 Cookie（浏览器登录 drive.uc.cn 后复制）"),
    XUNLEI("迅雷网盘", "cloud.cookie.xunlei", "xunlei.com", true,
            "需填写迅雷云盘网页版 Cookie（浏览器登录 pan.xunlei.com 后复制）"),
    BAIDU("百度网盘", "cloud.cookie.baidu", "baidu.com", true,
            "需填写百度网盘 Cookie（至少包含 BDUSS；注意百度风控可能限制账号）"),
    YUN139("139网盘", "cloud.cookie.139", "139.com", false,
            "公开分享通常无需 Cookie；下载受限时可填写 yun.139.com 的 Cookie"),
    PAN123("123云盘", "cloud.cookie.123", "123pan.com", false,
            "公开分享通常无需 Cookie；如遇限流可填写网页版 Cookie"),
    PAN115("115网盘", "cloud.cookie.115", "115.com", false,
            "匿名可浏览分享；下载建议填写 115 网页版 Cookie（UID/CID/SEID/KID）"),
    GUANGYA("光鸭云盘", "cloud.cookie.guangya", "guangyapan.com", false,
            "公开分享无需登录，填写提取码即可"),
    LANZOU("蓝奏云", "cloud.cookie.lanzou", "lanzou.com", false,
            "无需登录，填写提取码即可"),
    ILANZOU("蓝奏云优享版", "cloud.cookie.ilanzou", "ilanzou.com", false,
            "无需登录，填写提取码即可");

    public final String displayName;
    /** 配置文件中保存 Cookie 的键 */
    public final String cookieKey;
    /** Cookie 默认生效域名（子域名自动匹配） */
    public final String cookieDomain;
    /** 是否必须登录凭据才能完成解析 */
    public final boolean loginRequired;
    /** 界面提示文案 */
    public final String credentialHint;

    CloudDrive(String displayName, String cookieKey, String cookieDomain,
               boolean loginRequired, String credentialHint) {
        this.displayName = displayName;
        this.cookieKey = cookieKey;
        this.cookieDomain = cookieDomain;
        this.loginRequired = loginRequired;
        this.credentialHint = credentialHint;
    }
}
