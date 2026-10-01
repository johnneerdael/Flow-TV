package io.github.aedev.flow.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.aedev.flow.data.folders.FolderAudioRef

@OptIn(UnstableApi::class)
internal class FolderArtworkBitmapLoader(
    private val context: Context,
    private val delegate: BitmapLoader,
    private val sizePx: Int,
) : BitmapLoader by delegate {
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != FolderAudioRef.SCHEME) return delegate.loadBitmap(uri)
        val future = SettableFuture.create<Bitmap>()
        val request =
            ImageRequest
                .Builder(context)
                .data(uri)
                .size(sizePx)
                .allowHardware(false)
                .listener(
                    onSuccess = { _, result -> future.set(result.image.toBitmap()) },
                    onError = { _, result -> future.setException(result.throwable) },
                    onCancel = { future.cancel(false) },
                ).build()
        val disposable = SingletonImageLoader.get(context).enqueue(request)
        future.addListener({ if (future.isCancelled) disposable.dispose() }, MoreExecutors.directExecutor())
        return future
    }
}
