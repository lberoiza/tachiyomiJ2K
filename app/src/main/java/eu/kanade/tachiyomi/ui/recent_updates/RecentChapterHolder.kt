package eu.kanade.tachiyomi.ui.recent_updates

import android.view.View
import androidx.core.content.ContextCompat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.android.synthetic.main.download_button.*
import kotlinx.android.synthetic.main.recent_chapters_item.*

/**
 * Holder that contains chapter item
 * Uses R.layout.item_recent_chapters.
 * UI related actions should be called from here.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @param listener a listener to react to single tap and long tap events.
 * @constructor creates a new recent chapter holder.
 */
class RecentChapterHolder(private val view: View, private val adapter: RecentChaptersAdapter) :
    BaseChapterHolder(view, adapter) {

    /**
     * Color of read chapter
     */
    private var readColor = view.context.getResourceColor(android.R.attr.textColorHint)

    /**
     * Color of unread chapter
     */
    private var unreadColor = view.context.getResourceColor(android.R.attr.textColorPrimary)

    /**
     * Currently bound item.
     */
    private var item: RecentChapterItem? = null

    init {
        manga_cover.setOnClickListener {
            adapter.coverClickListener.onCoverClick(adapterPosition)
        }
    }

    /**
     * Set values of view
     *
     * @param item item containing chapter information
     */
    fun bind(item: RecentChapterItem) {
        this.item = item

        // Set chapter title
        chapter_title.text = item.chapter.name

        // Set manga title
        manga_full_title.text = item.manga.title

        if (front_view.translationX == 0f) {
            read.setImageDrawable(
                ContextCompat.getDrawable(
                    read.context, if (item.read) R.drawable.eye_off
                    else R.drawable.eye
                )
            )
        }

        // Set cover
        GlideApp.with(itemView.context).clear(manga_cover)
        if (!item.manga.thumbnail_url.isNullOrEmpty()) {
            GlideApp.with(itemView.context)
                    .load(item.manga)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .circleCrop()
                    .into(manga_cover)
        }

        // Check if chapter is read and set correct color
        if (item.chapter.read) {
            chapter_title.setTextColor(readColor)
            manga_full_title.setTextColor(readColor)
        } else {
            chapter_title.setTextColor(unreadColor)
            manga_full_title.setTextColor(unreadColor)
        }

        // Set chapter status
        notifyStatus(item.status, item.progress)
        resetFrontView()
    }

    private fun resetFrontView() {
        if (front_view.translationX != 0f) itemView.post { adapter.notifyItemChanged(adapterPosition) }
    }

    override fun getFrontView(): View {
        return front_view
    }

    override fun getRearRightView(): View {
        return right_view
    }

    /**
     * Updates chapter status in view.
     *
     * @param status download status
     */
    fun notifyStatus(status: Int, progress: Int) =
        download_button.setDownloadStatus(status, progress)
}