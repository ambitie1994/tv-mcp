package com.ambitie.tvmcp.util

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIpv4 {
    fun addresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        address.hostAddress?.let { result += it }
                    }
                }
            }
        }
        return result.distinct().sorted()
    }
}
