package io.github.HanJason233.chimera.data.model

/**
 * IPv6 路由模式
 */
enum class IPv6RoutingMode {
    /**
     * 禁用 IPv6 (默认)
     * DNS策略: prefer_ipv4, 无 inet6_address
     */
    DISABLED,
    
    /**
     * 启用 IPv6 (双栈)
     * DNS策略: prefer_ipv4, 添加 inet6_address
     */
    ENABLED,
    
    /**
     * 优先 IPv6
     * DNS策略: prefer_ipv6, 添加 inet6_address
     */
    PREFER
}
