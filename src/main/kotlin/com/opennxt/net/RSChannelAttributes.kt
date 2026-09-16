package com.opennxt.net

import com.opennxt.net.login.LoginType
import com.opennxt.util.ISAACCipher
import io.netty.channel.Channel
import io.netty.util.AttributeKey

object RSChannelAttributes {
    val LOGIN_UNIQUE_ID = AttributeKey.newInstance<Long>("login-unique-id")

    val LOGIN_TYPE = AttributeKey.newInstance<LoginType>("login-type")
    val LOGIN_USERNAME = AttributeKey.newInstance<String>("login-username")

    val LOGIN_AUTHENTICATED = AttributeKey.newInstance<Boolean>("login-authenticated")

    val LOGIN_AUTHENTICATED_USERNAME = AttributeKey.newInstance<String>("login-authenticated-username")

    val INCOMING_ISAAC = AttributeKey.newInstance<ISAACCipher>("incoming-isaac")
    val OUTGOING_ISAAC = AttributeKey.newInstance<ISAACCipher>("outgoing-isaac")

    val SIDE = AttributeKey.newInstance<Side>("side")
    val PASSTHROUGH_CHANNEL = AttributeKey.newInstance<Channel>("passthrough-channel")

    val CONNECTED_CLIENT = AttributeKey.newInstance<ConnectedClient>("connected-client")
}
