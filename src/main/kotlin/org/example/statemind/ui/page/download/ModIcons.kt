package org.example.statemind.ui.page.download

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import javafx.application.Platform
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.scene.shape.Rectangle
import javax.imageio.ImageIO
import org.example.statemind.core.GameDir
import org.example.statemind.core.auth.Http

/**
 * Modrinth 的项目图标。
 *
 * 三件事决定了它「慢」：
 *  1. 图标地址是 `_96.webp`，而 JavaFX 根本没有 webp 解码器 —— 直接 `Image(url)`
 *     只会得到一片灰。这里用 ImageIO 解码，再自己搬进 [WritableImage]。
 *  2. 每张图都是一个独立请求。走 JavaFX 自带的加载器时每张各建一条连接，
 *     冰岛到国内的链路上单是 TLS 握手就够呛；改走 [Http] 的共享客户端，同一批图标复用连接。
 *  3. 解码过的图存一份到本地缓存目录：第二次进同一个页面直接读盘。
 *
 * 下载与解码放在两条后台线程里串成队列：一行行往下滑时不会一次性开几十条连接，
 * 出来的图也基本按顺序到位。
 */
internal object ModIcons {

    private const val WORKERS = 2
    private const val MEMORY_LIMIT = 300

    private val memory = HashMap<String, Image>()
    private val waiting = HashMap<String, MutableList<Pair<StackPane, Double>>>()
    private val queued = HashSet<String>()
    private val queue = LinkedBlockingQueue<String>()

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun view(url: String?, size: Double): StackPane {
        val holder = placeholder(size)
        if (url.isNullOrBlank()) return holder

        val ready = synchronized(this) { memory[url] }
        if (ready != null) {
            holder.children += imageView(ready, size)
            return holder
        }

        synchronized(this) {
            waiting.getOrPut(url) { ArrayList() } += holder to size
            if (queued.add(url)) queue += url
        }
        startWorkers()
        return holder
    }

    private fun startWorkers() {
        if (!started.compareAndSet(false, true)) return
        repeat(WORKERS) { index ->
            Thread({ loop() }, "mod-icon-$index").apply { isDaemon = true; start() }
        }
    }

    private fun loop() {
        while (true) {
            val url = runCatching { queue.take() }.getOrNull() ?: continue
            val image = runCatching { load(url) }.getOrNull()
            val targets = synchronized(this) {
                queued.remove(url)
                if (image == null) {
                    // 失败的不留痕：下次滑到再试一次，总比默默记成「这张没有图」好
                    waiting.remove(url)
                    emptyList()
                } else {
                    if (memory.size >= MEMORY_LIMIT) memory.clear()
                    memory[url] = image
                    waiting.remove(url).orEmpty()
                }
            }
            if (image == null) continue
            Platform.runLater {
                targets.forEach { (holder, size) -> holder.children.setAll(imageView(image, size)) }
            }
        }
    }

    /** 先看本地缓存，没有再下载；原始字节存盘，换解码尺寸也不用重下。 */
    private fun load(url: String): Image? {
        val cached = cacheFile(url)
        val bytes = if (cached.isFile) {
            cached.readBytes()
        } else {
            val fetched = Http.getBytes(url) ?: return null
            runCatching {
                cached.parentFile?.mkdirs()
                cached.writeBytes(fetched)
            }
            fetched
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Image? {
        val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val width = buffered.width
        val height = buffered.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        buffered.getRGB(0, 0, width, height, pixels, 0, width)
        val image = WritableImage(width, height)
        image.pixelWriter.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width)
        return image
    }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + ".img"
        return File(File(GameDir.appRoot(), "cache/icons"), name)
    }

    private fun placeholder(size: Double): StackPane = StackPane().apply {
        minWidth = size
        prefWidth = size
        maxWidth = size
        minHeight = size
        prefHeight = size
        maxHeight = size
        // 图没到之前是一块浅灰；比留白更像「这里本来就有图」
        style = "-fx-background-color: #eef0f4; -fx-background-radius: 8;"
        isMouseTransparent = true
    }

    private fun imageView(image: Image, size: Double): ImageView = ImageView(image).apply {
        fitWidth = size
        fitHeight = size
        isPreserveRatio = true
        isSmooth = true
        // 方图裁成圆角，跟卡片其它圆角一致
        clip = Rectangle(size, size).apply { arcWidth = 18.0; arcHeight = 18.0 }
    }
}
