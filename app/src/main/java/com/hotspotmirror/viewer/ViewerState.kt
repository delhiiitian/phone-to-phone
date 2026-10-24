package com.hotspotmirror.viewer

import com.hotspotmirror.core.Message

/** In-memory feed + connection state shared between ViewerService and the UI. */
object ViewerState {
    @Volatile var connected: Boolean = false
    @Volatile var senderName: String? = null
    @Volatile var devices: List<Message.Device> = emptyList()
    val feed = java.util.concurrent.CopyOnWriteArrayList<Message>()
    @Volatile var onChange: (() -> Unit)? = null

    fun add(m: Message) { feed.add(0, m); while (feed.size > 200) feed.removeAt(feed.size - 1); onChange?.invoke() }
    fun changed() { onChange?.invoke() }
}
